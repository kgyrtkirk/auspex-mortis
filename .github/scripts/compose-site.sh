#!/bin/bash
# Lays out what gets published: a p2 composite whose children are this build's own
# repository and the sites it resolves against, so that adding one URL to an IDE brings
# Memory Analyzer, the MCP server and Calcite along with these tools. The product archive sits
# beside it, linked from index.html, for a machine that has no Eclipse.
#
# The children are read out of the target platform rather than written here twice: the
# repositories this was built against are the repositories it must be installed from. The
# release train is excluded - an IDE already has the platform, and offering the whole
# SimRel through this site would say nothing true about it.
#
#   .github/scripts/compose-site.sh update-site/*/target/repository site product/*/target/products/*.tar.gz
set -euo pipefail

repository=${1:?the built p2 repository to publish}
site=${2:?the directory to lay the published site out in}
product=${3:?the product archive to publish beside it}
target=target-platform/hu.rxd.auspex.mortis.target/hu.rxd.auspex.mortis.target.target

mapfile -t children < <(grep -o 'location="[^"]*"' "$target" | sed 's/^location="//; s/"$//' \
	| grep -v 'download.eclipse.org/releases' | sort -u)
if [ ${#children[@]} -eq 0 ]; then
	echo "no child repositories found in $target - the composite would be pointless" >&2
	exit 1
fi

rm -rf "$site"
mkdir -p "$site/auspex"
cp -r "$repository"/. "$site/auspex/"
cp "$product" "$site/"

archive=$(basename "$product")
cat >"$site/index.html" <<EOF
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Auspex Mortis</title></head>
<body>
<h1>Auspex Mortis</h1>
<p><a href="$archive">$archive</a></p>
</body>
</html>
EOF

# milliseconds, as p2 writes it: a client tells a changed composite from a cached one by
# this value alone
stamp=$(date +%s%3N)
count=$((${#children[@]} + 1))

write_composite() {
	local file=$1 kind=$2
	{
		echo "<?xml version='1.0' encoding='UTF-8'?>"
		echo "<?composite${kind}Repository version='1.0.0'?>"
		echo "<repository name='Auspex Mortis' type='org.eclipse.equinox.internal.p2.${3}.repository.Composite${kind}Repository' version='1.0.0'>"
		echo "  <properties size='2'>"
		echo "    <property name='p2.timestamp' value='${stamp}'/>"
		# false on purpose: a child that is down degrades this site to what the other
		# children hold, instead of making it unreadable
		echo "    <property name='p2.atomic.composite.loading' value='false'/>"
		echo "  </properties>"
		echo "  <children size='${count}'>"
		printf "    <child location='%s'/>\n" auspex "${children[@]}"
		echo "  </children>"
		echo "</repository>"
	} >"$site/$file"
}

write_composite compositeContent.xml Metadata metadata
write_composite compositeArtifacts.xml Artifact artifact

# without this p2 tries content.xml first and only then the composite, which costs every
# client a 404 before it finds anything
cat >"$site/p2.index" <<'EOF'
version=1
metadata.repository.factory.order=compositeContent.xml,\!
artifact.repository.factory.order=compositeArtifacts.xml,\!
EOF

echo "composed $site with children: auspex ${children[*]}"
