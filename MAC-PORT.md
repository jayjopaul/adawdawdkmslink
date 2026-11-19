# Slinky macOS / Forge 1.8.9 port

This branch replaces the Windows-only remote DLL injection path with a normal Forge 1.8.9 coremod/LaunchWrapper path.

## What changed

- No `Slinky.exe`.
- No Windows process injection.
- No JNI/JVMTI bridge is required for startup.
- Existing `SlinkyPatches` are applied while Minecraft classes are loaded by LaunchWrapper.
- Existing `SlinkyMod`, module implementations, UI, configuration, rendering hooks and packet hooks remain in the Java payload.
- The output is a single Java 8-compatible JAR intended for Forge 1.8.9.

## Build on macOS

Install a JDK with `javac` (JDK 17 or 21 is fine for this build), then run:

```bash
chmod +x build-mac.sh
./build-mac.sh
```

Output:

```text
build/mac/Slinky-Mac-1.8.9.jar
```

## Install

Use a Minecraft 1.8.9 Forge installation and place `Slinky-Mac-1.8.9.jar` in that installation's `mods` folder alongside Essential.

This port is intentionally a JVM-native loader rather than a macOS process injector.
