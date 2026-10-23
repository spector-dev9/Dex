# Environment audit — 2026-10-23

## Host

| Item | Value |
| --- | --- |
| Platform | Termux, Linux 6.1.157-android14-11 (aarch64) |
| Android API level | 36 (Android 14 vendor image) |
| ABI | arm64-v8a |
| CPU | 8 cores |
| Page size | 4096 bytes (not a 16 KB-page device) |
| Free disk | ~91 GiB on /data |
| Shell | bash |

## Toolchain

| Tool | Status | Notes |
| --- | --- | --- |
| git | 2.56.0 | available |
| node | 24.22.1 | available |
| npm | 11.21.1 | available |
| python3 | 3.14.8 | available |
| clang | available | compiler present |
| make | available | |
| proot | available | useful for host-side runtime experiments |
| curl / jq / tar / gzip | available | runtime fetch and inspection |
| java / javac | 21.0.13 | OpenJDK installed 2026-10-23 |
| Gradle | 8.13 | project wrapper; downloaded distribution |
| kotlinc | missing | Kotlin compiles through the Gradle plugin |
| Android SDK | partial | manual platform 36 + build-tools 36.0.0 layout |
| sdkmanager | missing | platform downloaded manually |
| adb | available | android-tools; no authorized device |
| cargo / rustc | missing | |
| cmake | missing | |
| apksigner | available | Termux package |
| aapt2 / aapt / zipalign / d8 | available | Termux aarch64 packages |

## Implications

1. **Kotlin/JVM contracts build locally; Android cannot.** JDK 21 and the
   Gradle 8.13 wrapper are available, so the `core/*-api` modules compile
   and package on this host. A partial Android platform/build-tools layout
   is present, but local APK assembly is unsupported; debug/release builds
   and device tests run in CI or on another machine.
2. **Device verification needs external access.** adb is installed but no
   authorized device is available to Termux, so device spikes (S1, S2, S7,
   S8) need a test device farm, a CI emulator, or manual installs.
3. **16 KB-page validation is not possible here.** The host reports
   4096-byte pages. RT-5 needs a 16 KB-page device or emulator.
4. **proot enables limited runtime experiments.** Upstream OpenCode can
   be exercised on the host under proot, but that does not validate the
   app sandbox, targetSdk 36 exec restrictions, or W^X. Treat results as
   directional only.
5. **Node/Python plus the JVM cover Phase 0–2 offline work:** contract
   fixtures, SSE tests, bridge protocol types, core Kotlin compilation,
   manifest verification scripts, and runtime download/inspection
   tooling.

## What can be verified locally

- Pure-logic TypeScript/JavaScript tests and contract tests.
- Manifest/sha-256 verification scripts.
- SSE parser and resync algorithm against recorded fixtures.
- Runtime artifact inspection (ELF alignment, dynamic dependencies)
  with readelf/llvm tools if installed or obtainable.
- UI build and typecheck.
- Kotlin/JVM contract compilation and packaging through the Gradle
  wrapper.
- Android asset/resource merging and manifest merging (`syncLicenses`,
  `mergeDebugAssets`, `mergeDebugResources`, `processDebugMainManifest`).

## What cannot be verified locally

- Android Kotlin compilation, APK assembly and signing on this host;
  build-tools 36.0.0 has no `split-select`.
- Emulator/device boot, install, runtime start, thermal/background
  behavior, keyboard/IME behavior, 16 KB-page boot.
