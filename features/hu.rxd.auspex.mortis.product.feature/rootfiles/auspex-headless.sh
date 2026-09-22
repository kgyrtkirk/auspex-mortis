#!/bin/bash
# Runs this installation with nobody watching: opens a dump and prints the MCP endpoint.
#
#   ./auspex-headless.sh <dump> [workspace]
#
# It drives the product it sits in, so it needs no path to be told. Somewhere else, or driving
# another installation, set AUSPEX_PRODUCT to the directory holding the auspex launcher.
#
# The workbench really runs - MAT's editors, its parse job and its panes are what the tools
# work through - so it runs against a virtual display. The dump is opened through the endpoint
# rather than on the command line, because Memory Analyzer's application reads no file
# argument: --launcher.openFile is an IDE feature and this product is not the IDE.
#
# The server needs no enabling; the product listens out of the box. What is set here is what a
# run must not share with the IDE its user may be sitting in: its own port, and its own bearer
# token beside the workspace rather than the one in ~/.eclipse.
#
# Env: AUSPEX_PRODUCT (this script's directory), AUSPEX_PORT (8642), AUSPEX_HEAP (8g),
#      AUSPEX_DISPLAY (:99), AUSPEX_WAIT (900),
#      AUSPEX_OPEN_WAIT (60, how long the open call waits before leaving the parse running)
set -euo pipefail

product=${AUSPEX_PRODUCT:-$(cd "$(dirname "$0")" && pwd)}
dump=${1:?the heap dump to open}
workspace=${2:-$(mktemp -d /tmp/auspex-XXXXXX)}
port=${AUSPEX_PORT:-8642}
heap=${AUSPEX_HEAP:-8g}
display=${AUSPEX_DISPLAY:-:99}
wait_seconds=${AUSPEX_WAIT:-900}
open_wait=${AUSPEX_OPEN_WAIT:-60}

[ -x "$product/auspex" ] || {
	echo "no auspex launcher in $product - set AUSPEX_PRODUCT to the installation to drive" >&2
	exit 1
}
[ -r "$dump" ] || { echo "cannot read the dump $dump" >&2; exit 1; }
for tool in Xvfb curl jq; do
	command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }
done

endpoint="$workspace/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"
settings="$workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
mkdir -p "$settings"
cat >"$settings/com.vogella.eclipse.mcp.server.prefs" <<EOF
eclipse.preferences.version=1
port=$port
EOF

if ! xdpyinfo -display "$display" >/dev/null 2>&1; then
	Xvfb "$display" -screen 0 1920x1080x24 -nolisten tcp &
	echo "started Xvfb on $display (pid $!)"
fi

DISPLAY=$display "$product/auspex" -data "$workspace" -nosplash -consoleLog \
	-vmargs -Xmx"$heap" "-Dcom.vogella.eclipse.mcp.tokenDirectory=$workspace/.mcp" &
launcher=$!
echo "started $product/auspex (pid $launcher), workspace $workspace, heap $heap"

deadline=$((SECONDS + wait_seconds))
until [ -r "$endpoint" ] || [ $SECONDS -ge $deadline ] || ! kill -0 $launcher 2>/dev/null; do
	sleep 1
done
if [ ! -r "$endpoint" ]; then
	echo "no endpoint after ${wait_seconds}s; the log is $workspace/.metadata/.log" >&2
	exit 1
fi
cat "$endpoint"

url=$(jq -r .url "$endpoint")
header=(-H "Authorization: Bearer $(jq -r .token "$endpoint")" -H "Content-Type: application/json"
	-H "Accept: application/json, text/event-stream")
session=$(curl -sS -D - -o /dev/null "${header[@]}" "$url" \
	-d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"auspex-headless","version":"1"}}}' \
	| grep -i '^mcp-session-id:' | tr -d '\r' | cut -d' ' -f2)
[ -n "$session" ] || { echo "the server did not open a session" >&2; exit 1; }
header+=(-H "Mcp-Session-Id: $session")
curl -sS "${header[@]}" -o /dev/null -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$url"

# a parse of a large dump outlives this call on purpose: it answers 'parsing', the work
# carries on in the product, and the agent asks mat_open again
answer=$(curl -sS "${header[@]}" "$url" -d "$(printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mat_open","arguments":{"path":"%s","waitSeconds":%s}}}' "$dump" "$open_wait")")
curl -sS -X DELETE "${header[@]}" -o /dev/null "$url" || true
frame=$(printf '%s' "$answer" | sed -n 's/^data: //p')
printf '%s' "${frame:-$answer}" | jq -r '.result.content[0].text'
