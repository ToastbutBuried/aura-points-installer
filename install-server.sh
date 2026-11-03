#!/usr/bin/env bash
# Aura Points server installer - Linux and macOS servers.
#
#   mkdir aura-server && cd aura-server
#   curl -fsSL https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install-server.sh | bash
#
# In an empty folder it sets up a new NeoForge or Fabric server with Aura Points and everything it needs (asks
# which loader, how much memory, the EULA, and whether Aura Points should update itself on every start).
# In a folder that already holds a NeoForge or Fabric server it only puts the newest Aura Points (and any
# missing mod it needs) into mods/ - running it again later is how you update.
#
# Needs Java 21: uses yours if there is one, otherwise downloads Eclipse Temurin 21 into <folder>/java
# (kept - the server runs on it). Extra arguments go to the installer, e.g.
#   ... | bash -s -- --dir /srv/aura --loader fabric --ram 6G --eula --yes
set -e
RAW=https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main
DIR=.
prev=
for a in "$@"; do
	[ "$prev" = --dir ] && DIR="$a"
	prev="$a"
done
mkdir -p "$DIR"
DIR="$(cd "$DIR" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

major() {
	"$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

find_java() {
	local c v
	for c in "$DIR/java/bin/java" "$DIR"/java/*/bin/java "$DIR"/java/*/Contents/Home/bin/java \
		"$JAVA_HOME/bin/java" "$(command -v java 2>/dev/null)" \
		/usr/lib/jvm/*/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
		[ -n "$c" ] && [ -x "$c" ] || continue
		v="$(major "$c" 2>/dev/null)"
		if [ -n "$v" ] && [ "$v" -ge 21 ] 2>/dev/null; then
			echo "$c"
			return 0
		fi
	done
	return 1
}

echo "Looking for Java 21 ..."
if ! JAVA="$(find_java)"; then
	echo "No Java 21 found - downloading Eclipse Temurin 21 into $DIR/java ..."
	case "$(uname -s)" in Darwin) os=mac ;; *) os=linux ;; esac
	case "$(uname -m)" in arm64 | aarch64) arch=aarch64 ;; *) arch=x64 ;; esac
	curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jre/hotspot/normal/eclipse" -o "$WORK/jre.tar.gz"
	mkdir -p "$DIR/java" && tar -xzf "$WORK/jre.tar.gz" -C "$DIR/java"
	JAVA="$(find "$DIR/java" -path '*/bin/java' -type f | head -1)"
	[ -x "$JAVA" ] || { echo "Couldn't get Java. Install Java 21 (https://adoptium.net) and run this again."; exit 1; }
fi
echo "Using $JAVA"

curl -fsSL "$RAW/aura-installer.jar" -o "$WORK/aura-installer.jar"
# --dir first so a --dir in "$@" (the same folder) wins; read answers from the keyboard even through a pipe
if [ ! -t 0 ] && (exec </dev/tty) 2>/dev/null; then
	"$JAVA" -jar "$WORK/aura-installer.jar" --server --dir "$DIR" --java "$JAVA" "$@" </dev/tty
else
	"$JAVA" -jar "$WORK/aura-installer.jar" --server --dir "$DIR" --java "$JAVA" "$@"
fi
