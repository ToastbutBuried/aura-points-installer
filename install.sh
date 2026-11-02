#!/usr/bin/env bash
# Aura Points server installer - Linux and macOS.
#
#   curl -fsSL https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main/install.sh | bash
#
# Finds a Java 17+ (yours, Prism's, the Minecraft Launcher's or the Modrinth App's - or downloads one
# into a temp folder), then runs aura-installer.jar, which asks for a name and which mods, finds your
# launcher and makes the instance. Extra arguments are passed on to it (bash -s -- --name "My Server").
set -e
RAW=https://raw.githubusercontent.com/ToastbutBuried/aura-points-installer/main
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# major version of a java binary (8 for "1.8.0", 21 for "21.0.7"), 0 if it doesn't run
major() {
	"$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

find_java() {
	local sup="$HOME/Library/Application Support" c v
	for c in "$JAVA_HOME/bin/java" "$(command -v java 2>/dev/null)" \
		"$HOME"/.local/share/PrismLauncher/java/*/bin/java \
		"$HOME"/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/*/bin/java \
		"$sup"/PrismLauncher/java/*/bin/java "$sup"/PrismLauncher/java/*/Contents/Home/bin/java \
		"$HOME"/.minecraft/runtime/java-runtime-*/*/java-runtime-*/bin/java \
		"$sup"/minecraft/runtime/java-runtime-*/*/java-runtime-*/jre.bundle/Contents/Home/bin/java \
		"$HOME"/.local/share/ModrinthApp/meta/java_versions/*/bin/java \
		"$sup"/ModrinthApp/meta/java_versions/*/Contents/Home/bin/java \
		/usr/lib/jvm/*/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
		[ -n "$c" ] && [ -x "$c" ] || continue
		v="$(major "$c" 2>/dev/null)"
		if [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null; then
			echo "$c"
			return 0
		fi
	done
	return 1
}

echo "Looking for Java ..."
if ! JAVA="$(find_java)"; then
	echo "No Java 17+ found - downloading a temporary one (Eclipse Temurin 21, deleted afterwards) ..."
	case "$(uname -s)" in Darwin) os=mac ;; *) os=linux ;; esac
	case "$(uname -m)" in arm64 | aarch64) arch=aarch64 ;; *) arch=x64 ;; esac
	curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jre/hotspot/normal/eclipse" -o "$WORK/jre.tar.gz"
	mkdir "$WORK/jre" && tar -xzf "$WORK/jre.tar.gz" -C "$WORK/jre"
	JAVA="$(find "$WORK/jre" -path '*/bin/java' -type f | head -1)"
	[ -x "$JAVA" ] || { echo "Couldn't get Java. Install Java 21 (https://adoptium.net) and run this again."; exit 1; }
fi
echo "Using $JAVA"

curl -fsSL "$RAW/aura-installer.jar" -o "$WORK/aura-installer.jar"
# read answers from the keyboard even when this script itself came in through a pipe
if [ ! -t 0 ] && (exec </dev/tty) 2>/dev/null; then
	"$JAVA" -jar "$WORK/aura-installer.jar" "$@" </dev/tty
else
	"$JAVA" -jar "$WORK/aura-installer.jar" "$@"
fi
