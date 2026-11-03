#!/bin/bash
# Publishes an Aura Points release: GitHub Release vX.Y.Z on the mod's repo (ToastbutBuried/aura-points) with
# both jars, then two update manifests in that repo's root: latest.json (NeoForge - old updaters only know this
# one, so its top-level fields stay as they were) and latest-fabric.json. The installer, the server installer and
# every updater read them from there and pick the one for their loader.
# Usage: ./publish.sh 5.2.0 "release notes"
set -e
V="$1"; NOTES="${2:-Aura Points $1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MOD=/home/toast/Downloads/aura-points-neoforge
REPO=ToastbutBuried/aura-points
JAR=$MOD/neoforge/build/libs/aura-points-neoforge-$V.jar
FJAR=$MOD/fabric/build/libs/aura-points-fabric-$V.jar
[ -f "$JAR" ] || { echo "no jar $JAR"; exit 1; }
[ -f "$FJAR" ] || { echo "no jar $FJAR"; exit 1; }
prop() { grep "^$1=" "$MOD/gradle.properties" | cut -d= -f2-; }
MC=$(prop minecraft_version)

# manifest <loader> <loader version> <jar> <requires file> <out>
manifest() {
	local f; f=$(basename "$3")
	cat > "$5" <<JSON
{
  "version": "$V",
  "file": "$f",
  "url": "https://github.com/$REPO/releases/download/v$V/$f",
  "sha256": "$(sha256sum "$3" | cut -d' ' -f1)",
  "loader": "$1",
  "minecraft": "$MC",
  "loader_version": "$2",
  "requires": $(cat "$4")
}
JSON
}

cd "$MOD"
# the release tag must exist on GitHub first (tools/deploy.sh tags; push it here)
git push -q origin "v$V" 2>/dev/null || true
~/.local/bin/gh release create "v$V" "$JAR" "$FJAR" -R "$REPO" --verify-tag --title "Aura Points $V" --notes "$NOTES"
manifest neoforge "$(prop neoforge_version)" "$JAR" "$HERE/requires-neoforge.json" latest.json
manifest fabric "$(prop fabric_loader_version)" "$FJAR" "$HERE/requires-fabric.json" latest-fabric.json
git add latest.json latest-fabric.json
git commit -q -m "latest.json + latest-fabric.json: Aura Points $V

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -q origin HEAD
echo "published $V"
