# Environment audit — 2026-10-23

## Host

| Item | Value |
| --- | --- |
| Platform | Termux, Linux 6.1.157-android14-11 (aarch64) |
| Android API level | 36 (Android 14 vendor image) |
| ABI | arm64-v8a |
| CPU | 8 cores |
| Page size | 4096 bytes (not a 16 KB-page device) |
| Free disk | ~94 GiB on /data |
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
| java / javac | missing | no JVM |
| gradle | missing | |
| kotlinc | missing | |
| Android SDK / sdkmanager | missing | |
| adb | missing | no device bridge |
| cargo / rustc | missing | |
| cmake | missing | |
| apksigner | missing | |

## Implications

1. **Kotlin/Android cannot be built in this workspace.** No JDK, Gradle,
   Android SDK, NDK or adb. Options: build in GitHub Actions (as the
   legacy project did), or install a JVM toolchain in Termux and use
   command-line Android tools. CI is the default.
2. **Device verification needs external access.** The host is an
   Android device, but without adb/root Termux cannot install or drive
   the app under test. Device spikes (S1, S2, S7, S8) require a test
   device farm, a CI emulator, or manual installs.
3. **16 KB-page validation is not possible here.** The host reports
   4096-byte pages. RT-5 needs a 16 KB-page device or emulator.
4. **proot enables limited runtime experiments.** Upstream OpenCode can
   be exercised on the host under proot, but that does not validate the
   app sandbox, targetSdk 36 exec restrictions, or W^X. Treat results as
   directional only.
5. **Node/Python are sufficient for Phase 0–2 offline work:** contract
   fixtures, SSE tests, bridge protocol types, manifest verification
   scripts, and runtime download/inspection tooling.

## What can be verified locally

- Pure-logic TypeScript/JavaScript tests and contract tests.
- Manifest/sha-256 verification scripts.
- SSE parser and resync algorithm against recorded fixtures.
- Runtime artifact inspection (ELF alignment, dynamic dependencies)
  with readelf/llvm tools if installed or obtainable.
- UI build and typecheck.

## What cannot be verified locally

- Gradle/Kotlin compilation, APK assembly, signing.
- Emulator/device boot, install, runtime start, thermal/background
  behavior, keyboard/IME behavior, 16 KB-page boot.
