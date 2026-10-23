# S3 device harness run — 2026-10-24

Raw report: [`raw/spike-device-20261024-025251.json`](raw/spike-device-20261024-025251.json)

- SHA-256: `f2a4c6645c8745d20930093278b6d0ba509829f47c177fc81fe1fd92c764692f`
- Device: Infinix X6870, Android 16 (SDK 36), arm64-v8a, 4 KB pages
- APK: CI compile-only `spike-harness-debug.apk`; runtime staged manually with
  `scripts/stage-spike-harness.py`
- ADB: wireless debugging; no production app changes

## Results

| Probe | Result | Detail |
| --- | --- | --- |
| `runtime-layout` | PASS | staged runtime complete |
| `S1-native-loader` | FAIL | `libldmusl.so` absent from `nativeLibraryDir` |
| `S1-loader` | FAIL | `libldmusl.so` absent from `nativeLibraryDir` |
| `S3-native-proot` | FAIL | `libproot.so` absent from `nativeLibraryDir` |
| `S3-native-proot-loader` | FAIL | `libproot_loader.so` absent from `nativeLibraryDir` |
| `S3-guest-shell` | FAIL | `proot` exec from app data: `error=13, Permission denied` |
| `S3-native-pty` | FAIL | `exit=127`; no terminal size or guest output |
| `S7-fgs-start` | PASS | `startForegroundService` requested |
| `S7-service` | PASS | `onCreate`, `startForeground`, `onDestroy` observed |
| `S7-fgs-stop` | PASS | `stopService` requested |

## Interpretation

The CI APK is compile-only: it has `libptyprobe.so` but not the staged
`libldmusl.so`, `libproot.so` or `libproot_loader.so`. The harness fallback
to execute `proot` from app-private storage is blocked by Android's
no-execute policy for app data (`EACCES`). The native `forkpty` probe then
exits 127 before the guest shell starts.

This run is **not** S3 or G0 acceptance. It identifies the packaging
requirement: build and install the harness APK **after** staging, so the
loader and PRoot binaries are packaged in `nativeLibraryDir`, then rerun
S1/S3.

## Rootfs symlink note

Alpine's `rootfs/bin/sh` is an absolute symlink to `/bin/busybox`. Android
resolves that against the phone, not the guest rootfs. The device run used
a relative `sh -> busybox` link so the harness `isFile` checks passed.
`runGuestShell` still failed on the `proot` exec restriction above.
