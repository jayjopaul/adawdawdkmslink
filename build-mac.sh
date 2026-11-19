#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BUILD="$ROOT/build/mac"
TOOLS="$BUILD/tools"
CLASSES="$BUILD/classes"
EXTERNAL="$BUILD/external"
MAPPING_ROOT="$BUILD/mapping"

rm -rf "$TOOLS" "$CLASSES" "$MAPPING_ROOT"
mkdir -p "$TOOLS" "$CLASSES" "$EXTERNAL" "$MAPPING_ROOT/dev/slinky/inject"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

command -v "$JAVAC" >/dev/null 2>&1 || { echo "javac not found. Install a JDK (17 or 21 recommended)."; exit 1; }
command -v "$JAVA" >/dev/null 2>&1 || { echo "java not found."; exit 1; }
command -v "$JAR" >/dev/null 2>&1 || { echo "jar not found."; exit 1; }

FORGE_VERSION="1.8.9-11.15.1.2318-1.8.9"
FORGE_JAR="$EXTERNAL/forge-$FORGE_VERSION-universal.jar"
LAUNCHWRAPPER_JAR="$EXTERNAL/launchwrapper-1.12.jar"

if [[ ! -f "$FORGE_JAR" ]]; then
  echo "Downloading Forge 1.8.9 compile API..."
  curl -fL --retry 3 --retry-delay 2 "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-universal.jar" -o "$FORGE_JAR"
fi

if [[ ! -f "$LAUNCHWRAPPER_JAR" ]]; then
  echo "Downloading LaunchWrapper..."
  curl -fL --retry 3 --retry-delay 2 "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar" -o "$LAUNCHWRAPPER_JAR"
fi

echo "Compiling remapping tool..."
"$JAVAC" --release 8 -encoding UTF-8 -cp "deps/tooling/*" -d "$TOOLS" tools/RemapJar.java

echo "Creating named Minecraft compile dependency..."
"$JAVA" -cp "$TOOLS:deps/tooling/*" RemapJar mappings/named-to-official.txt reverse deps/minecraft-1.8.9-official.jar "$BUILD/minecraft-1.8.9-named.jar" --mcp-exceptions

LIB_CP="$(find deps/libraries -type f -name '*.jar' | sort | paste -sd ':' -)"
CP="$BUILD/minecraft-1.8.9-named.jar:$LIB_CP:$FORGE_JAR:$LAUNCHWRAPPER_JAR"

echo "Compiling Slinky Java sources..."
find java/src/main/java -type f -name '*.java' | sort > "$BUILD/sources.txt"
"$JAVAC" --release 8 -encoding UTF-8 -classpath "$CP" -d "$CLASSES" @"$BUILD/sources.txt"

cat > "$BUILD/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Implementation-Title: Slinky Semantic macOS Forge loader
Implementation-Version: 0.5.0-settings-mac
FMLCorePlugin: dev.slinky.inject.SlinkyCorePlugin
FMLCorePluginContainsFMLMod: true
ForceLoadAsMod: true
EOF

echo "Packaging named intermediate..."
"$JAR" cfm "$BUILD/slinky-mac-named.jar" "$BUILD/MANIFEST.MF" -C "$CLASSES" . -C java/src/main/resources .

echo "Remapping Minecraft references to Forge/SRG runtime names..."
"$JAVA" -cp "$TOOLS:deps/tooling/*" RemapJar mappings/named-to-srg.txt forward "$BUILD/slinky-mac-named.jar" "$BUILD/Slinky-Mac-1.8.9.jar" "$BUILD/minecraft-1.8.9-named.jar"

cp java/mappings/srg.txt "$MAPPING_ROOT/dev/slinky/inject/mappings.txt"
"$JAR" ufm "$BUILD/Slinky-Mac-1.8.9.jar" "$BUILD/MANIFEST.MF" -C "$MAPPING_ROOT" dev/slinky/inject/mappings.txt

echo
echo "Built: $BUILD/Slinky-Mac-1.8.9.jar"
echo "This is a Forge/LaunchWrapper coremod JAR; it does not inject into another process."