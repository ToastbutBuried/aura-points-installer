#!/bin/bash
# Publishes an Aura Points jar: GitHub Release vX.Y.Z with the jar on the mod's repo (ToastbutBuried/aura-points),
# then latest.json in that repo's root pointing at it (the installer and every updater read it from there).
# Usage: ./publish.sh 5.2.0 "release notes"
set -e
V="$1"; NOTES="${2:-Aura Points $1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MOD=/home/toast/Downloads/aura-points-neoforge
REPO=ToastbutBuried/aura-points
JAR=$MOD/build/libs/aura-points-$V.jar
[ -f "$JAR" ] || { echo "no jar $JAR"; exit 1; }
cd "$MOD"
# the release tag must exist on GitHub first (tools/deploy.sh tags; push it here)
git push -q origin "v$V" 2>/dev/null || true
~/.local/bin/gh release create "v$V" "$JAR" -R "$REPO" --verify-tag --title "Aura Points $V" --notes "$NOTES"
SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
cat > latest.json <<JSON
{
  "version": "$V",
  "file": "aura-points-$V.jar",
  "url": "https://github.com/$REPO/releases/download/v$V/aura-points-$V.jar",
  "sha256": "$SHA"$( [ -f "$HERE/requires.json" ] && printf ',\n  "requires": %s' "$(cat "$HERE/requires.json")" )
}
JSON
git add latest.json
git commit -q -m "latest.json: Aura Points $V

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -q origin HEAD
echo "published $V ($SHA)"
