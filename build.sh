#!/bin/bash
# Builds aura-installer.jar and aura-updater.jar (Java 17 bytecode, no dependencies) into this folder.
set -e
cd "$(dirname "$0")"
for x in installer:AuraInstaller:aura-installer updater:AuraUpdater:aura-updater; do
	IFS=: read -r src main jar <<<"$x"
	rm -rf "build/$src" && mkdir -p "build/$src"
	javac --release 17 -d "build/$src" "$src/$main.java"
	jar --create --file "$jar.jar" --main-class "$main" -C "build/$src" .
done
echo built aura-installer.jar aura-updater.jar
