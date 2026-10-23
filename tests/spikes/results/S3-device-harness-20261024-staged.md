# S3/S1 device harness run — 2026-10-24, staged runtime APK

Raw report: [`raw/spike-device-20261024-034947.json`](raw/spike-device-20261024-034947.json)
Confirmation run: [`raw/spike-device-20261024-042222.json`](raw/spike-device-20261024-042222.json)

- SHA-256: `1b0d9a17074bec1cce3f2bdd85026802efbcefebd93c8af223ec9fc5ca9cda94`
- Confirmation run SHA-256: `e31c69e6ef403fb6cc5c7ad8430c75966e5c7da45dfd382eebb768cae7c15f91`
- Device: Infinix X6870, Android 16 (SDK 36), arm64-v8a, 4 KB pages
- APK: `spike-harness-debug.apk` 0.1.0-spike (minSdk 26, targetSdk 36) built
  **after** staging, so `nativeLibraryDir` packages the PTY probe plus
  `libldmusl.so`, `libproot.so` and `libproot_loader.so`
  (`extractNativeLibs=true`, path: `/data/app/~~K0PeoofXzlhok1R0lbdEtA==/.../lib/arm64`)
- ADB: wireless debugging (`adb install -r -t`), no production app changes

## Missing runtime problem

After a clean side-load of the harness APK the app reports every required
runtime file as missing:

```
Runtime directory: /data/user/0/io.github.spectordev9.opencode.spike.debug/files/spike
MISSING runtime/opencode            MISSING proot
MISSING runtime/ld-musl-aarch64.so.1  MISSING loader
MISSING runtime/libc.musl-aarch64.so.1  MISSING libtalloc.so.2
MISSING runtime/libgcc_s.so.1       MISSING libandroid-shmem.so
MISSING runtime/libstdc++.so.6      MISSING rootfs/bin/sh
```

Consequences seen live: `S1-loader` fails with
`missing .../files/spike/runtime/opencode` and `S3-guest-shell` falls back
to an app-data `proot` exec which Android blocks. The APK only ships the
`.so` wrappers; the 195 MB opencode/musl payload, PRoot, talloc,
libandroid-shmem and the Alpine rootfs live outside any APK because of
the Play Store binary-size limit, and the harness intentionally does not
download or execute files by itself (no network fetch in an app spike).

### How it was solved

The repo already ships the pinned staging payload
(`scripts/stage-spike-harness.py` output). It was deployed over wireless
ADB without root:

1. Verify the artifact:
   `sha256sum runtime/artifacts/spike-runtime.tar.gz`
   → `6590a1827d79bcdd26e93cf50b499551a48ad48c9ac3407cb7c2850c70211374`
   (matches `spike-runtime.tar.gz.sha256`)
2. Push: `adb push runtime/artifacts/spike-runtime.tar.gz /data/local/tmp/`
3. Extract into the app's private storage (debug build → `run-as` works):
   `adb shell run-as io.github.spectordev9.opencode.spike.debug sh -c 'cd files && tar xzf /data/local/tmp/spike-runtime.tar.gz'`
4. Verify with `run-as ... ls -laR files/spike` and the harness
   "Refresh device and runtime" button: all 10 required entries OK
   (`rootfs/bin/sh` shows 0 B because Alpine's `sh -> busybox` relative
   symlink was used, as in the previous run).

Staged contents: `opencode 1.19.3`, musl loader/libc
(`ld-musl-aarch64.so.1`, `libc.musl-aarch64.so.1`, each 914048 B),
`libgcc_s.so.1`, `libstdc++.so.6` (2820600 B), `proot`
(5.1.107.100), `loader`, `libtalloc.so.2` (2.5.0),
`libandroid-shmem.so` (0.7), Alpine 3.20.3 minirootfs with busybox.

## Results (first fully passing device run)

| Probe | Result | Detail |
| --- | --- | --- |
| `S1-native-loader` | PASS | `libldmusl.so` 723536 B, executable, minLoadAlign=65536 (16 KB-ok) |
| `S3-native-proot` | PASS | `libproot.so` 258576 B, executable, minLoadAlign=16384 (16 KB-ok) |
| `S3-native-proot-loader` | PASS | `libproot_loader.so` 18136 B, executable, minLoadAlign=16384 (16 KB-ok) |
| `runtime-layout` | PASS | staged runtime complete under `files/spike` |
| `S1-loader` | PASS | musl loader + `opencode --version`, exit=0, stdout `1.19.3` (2334 ms) |
| `S3-guest-shell` | PASS ×2 | PRoot guest: exit=0, PWD/PATH echoed, Alpine `3.20.3`, `GUEST-OK` (155/104 ms) |
| `S3-native-pty` | PASS | `forkpty` probe: exit=0, terminal size 80x24, environment match with guest shell (378 ms) |
| `S7-fgs-start` | PASS | `startForegroundService` requested |
| `S7-service` | PASS | `onCreate`, `startForeground onStartCommand startId=1`, `onDestroy` observed |
| `S7-fgs-stop` | PASS | `stopService` requested |

Additional observations:

- `PATH` inside the guest contains the host `/system/bin` prefixes
  because the harness passes them explicitly; Alpine is on 3.20.3 as
  staged.
- `node -v` is "not found" inside the minirootfs — informational only;
  `S3-guest-shell` passes on `GUEST-OK`, and opencode's own runtime can
  provide Node separately.
- The S8 keyboard/IME probe captures events only in the harness UI and
  is not part of the JSON records; it was not exercised in this run.
- Flush/deny noise from the vendor perf HAL (`perfctl`) appears in
  logcat but is unrelated to the harness probes.

## Confirmation run (2026-10-24 04:22 IST)

A repeat full pass on the same install/staging state, `generatedAt`
`2026-10-24T04:22:22+05:30`, report exported by the harness to the phone
Download folder and pulled from Termux storage access (no ADB needed).
All 13 records match the first run's outcomes:

| Probe | Result | Notes |
| --- | --- | --- |
| `S1-native-loader` / `S3-native-proot` / `S3-native-proot-loader` | PASS ×3 | identical ELF/alignment details |
| `runtime-layout` | PASS | unchanged staging |
| `S1-loader` | PASS | exit=0, `1.19.3` (1560 ms, down from 2334 ms — warm file cache) |
| `S3-guest-shell` | PASS ×2 | exit=0, `GUEST-OK` (53/104 ms) |
| `S3-native-pty` | PASS | exit=0, 80x24, environmentMatch=true (181 ms) |
| `S7-fgs-start` / `S7-service` / `S7-fgs-stop` | PASS | full lifecycle again (`onCreate` → `startForeground` → `onDestroy`) |

The repeat run rules out a one-off: loader exec, PRoot guest shell and
native PTY pass deterministically on this device state.

## Interpretation

With the staged runtime both packaging (`S1-native-loader`,
`S3-native-proot*`) and execution (`S1-loader`, `S3-guest-shell`,
`S3-native-pty`) now pass on the physical device, resolving every
blocker listed in [`S3-device-harness-20261024.md`](S3-device-harness-20261024.md):
no app-data exec was attempted and none was needed — all binaries ran
from `nativeLibraryDir` or 16 KB-aligned musl loading.

This is still the isolated pre-G0 harness: it proves the Phase 2
packaging + PTY strategy on device, and does not by itself promote an
acceptance row; the production app wiring (S2, RT-*, LC-*, TERM-*)
remains pending under `docs/evidence/RESULTS.md`.
