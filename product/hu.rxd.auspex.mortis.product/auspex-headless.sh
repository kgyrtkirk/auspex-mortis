#!/bin/bash
# Starts the product with nobody watching and prints the MCP endpoint to talk to it.
#
# The workbench really runs - MAT's editors, its parse job and its panes are what the tools
# work through - so it runs against a virtual display. Everything else here is what a
# preferences page would otherwise be needed for: the server is enabled in this workspace
# only, and the bearer token is kept beside it rather than in ~/.eclipse, so a run cannot
# disturb the IDE its user is sitting in.
#
#   auspex-headless.sh <product-dir> <dump> [workspace]
#
# Env: AUSPEX_PORT (8642), AUSPEX_HEAP (4g), AUSPEX_DISPLAY (:99), AUSPEX_WAIT (900)
set -euo pipefail

product=${1:?the unpacked product directory}
dump=${2:?the heap dump to open}
workspace=${3:-$(mktemp -d /tmp/auspex-XXXXXX)}
port=${AUSPEX_PORT:-8642}
heap=${AUSPEX_HEAP:-4g}
display=${AUSPEX_DISPLAY:-:99}
wait_seconds=${AUSPEX_WAIT:-900}

[ -x "$product/auspex" ] || { echo "no auspex launcher in $product" >&2; exit 1; }
[ -r "$dump" ] || { echo "cannot read the dump $dump" >&2; exit 1; }
command -v Xvfb >/dev/null || { echo "Xvfb is not installed; the workbench needs a display" >&2; exit 1; }

endpoint="$workspace/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"
settings="$workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
mkdir -p "$settings"
cat >"$settings/com.vogella.eclipse.mcp.server.prefs" <<EOF
eclipse.preferences.version=1
enabled=true
port=$port
EOF

if ! xdpyinfo -display "$display" >/dev/null 2>&1; then
	Xvfb "$display" -screen 0 1920x1080x24 -nolisten tcp &
	echo "started Xvfb on $display (pid $!)"
fi

# --launcher.openFile hands the path to the running instance over D-Bus, so without
# dbus-launch on the machine it is silently dropped. mat_open reaches the same state through
# the endpoint, so the run is not lost - but which of the two happened has to be said.
open=(--launcher.openFile "$dump")
if ! command -v dbus-launch >/dev/null; then
	open=()
	echo "no dbus-launch: the dump is not opened at startup, call mat_open with $dump" >&2
fi

DISPLAY=$display "$product/auspex" -data "$workspace" -nosplash -consoleLog "${open[@]}" \
	-vmargs -Xmx"$heap" "-Dcom.vogella.eclipse.mcp.tokenDirectory=$workspace/.mcp" &
launcher=$!
echo "started $product/auspex (pid $launcher), workspace $workspace"

# the endpoint appears when the server is listening, which is long before the parse ends:
# an agent connects first and watches the dump arrive through mat_open
deadline=$((SECONDS + wait_seconds))
until [ -r "$endpoint" ] || [ $SECONDS -ge $deadline ] || ! kill -0 $launcher 2>/dev/null; do
	sleep 1
done
if [ ! -r "$endpoint" ]; then
	echo "no endpoint after ${wait_seconds}s; the log is $workspace/.metadata/.log" >&2
	exit 1
fi
cat "$endpoint"
