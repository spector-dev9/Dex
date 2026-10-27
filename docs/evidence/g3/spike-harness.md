# Spike harness device run — S1/S3/TERM-5

Date: 2026-10-27. Device: Infinix X6870 (Android 16/API 36, arm64-v8a,
4 KB pages). Evidence class: isolated pre-G0 spike harness, **not** the
production app. The harness README warns that a passing harness run is not
device acceptance; this folder therefore records it as partial G3 evidence.

## Provenance

| Item | Value |
| --- | --- |
| APK | `spike-harness-debug.apk` from CI run `40499996517` (`app-apks` artifact) |
| APK SHA-256 | `e3d78b23e69338f065381461cdce6de60e52c5e2fc7264993c0705b675bc0cee` |
| Application ID | `io.github.spectordev9.opencode.spike.debug` |
| App ID (sandbox UID) | 10376 (production app is 10375) |
| Android security patch | 2026-08-01 |
| Raw report | [`spike-harness-report.json`](spike-harness-report.json) |
| Server trace | [`spike-harness-s1-server.log`](spike-harness-s1-server.log) |
| SELinux scan | [`spike-harness-exec-audit.txt`](spike-harness-exec-audit.txt) |

The harness downloaded the pinned runtime over HTTPS into app-private storage
and verified every SHA-256 before installation
(`runtime/manifest.json`). OpenCode `1.19.3` executable:
`37c7de554c2ba7bf456e4fe6faf7705141b0ccb3a7b1dd6e1046d52cd8269ecd`
(195,014,520 B); archive:
`39c9ff1ea47fd59752613079f97bef40a2d14c30bc6bb5772a91d9d708e2d0b3`.

## Result: 11/11 probes passed

| Probe | Result |
| --- | --- |
| `runtime-download` | PASS — pinned archives/executable verified, 26.8 s |
| `S1-native-loader` | PASS — `libldmusl.so`, min PT_LOAD align 65536 B |
| `S3-native-proot` | PASS — `libproot.so`, min PT_LOAD align 16384 B |
| `S3-native-proot-loader` | PASS — `libproot_loader.so`, min PT_LOAD align 16384 B |
| `runtime-layout` | PASS |
| `S1-loader` | PASS — `opencode --version` printed `1.19.3` (1.27 s) |
| `S1-server-health` | PASS — authenticated `/global/health` HTTP 200 (4.61 s) |
| `S1-mock-provider-prompt` | PASS — deterministic prompt round trip, 1 provider request |
| `S3-guest-shell` | PASS — Alpine 3.20.3, Node v20.15.1, `pwd=/root` |
| `S3-native-pty` | PASS — `forkpty` 80x24, environment matches guest shell |
| `TERM-5-pty-byte-roundtrip` | PASS — `61 62 63 0a 7f 09 1b 1b 5b 41 03 1b 78` |
| `device-suite-summary` | PASS — 11/11 available probes |

Representative commands (from the report):

```text
libldmusl.so --library-path .../files/spike/runtime .../runtime/opencode --version
libldmusl.so --library-path .../files/spike/runtime .../runtime/opencode \
  serve --hostname 127.0.0.1 --port 43687 --print-logs --log-level ERROR
libproot.so --kill-on-exit -0 -r .../files/spike/rootfs \
  -b /dev -b /proc -b /sys -w /root /bin/sh -c '<guest probe>'
forkpty libproot.so /bin/sh -i
```

The PTY byte round trip covers text, Enter (`CR` normalized to `LF` by
`ICRNL`), Backspace, Tab, Esc, Up, Ctrl-C and Alt-x. It validates PTY byte
transport only; physical Gboard mapping and the production terminal UI are
not exercised.

## What this adds to G3

- **RT-1/RT-2 (partial)**: first app-sandbox start of the pinned server on the
  reference device; authenticated health in 4.6 s. Airplane-mode and p95 runs
  are still absent.
- **RT-3 (partial)**: on-device hash verification of the executable and
  archives by the installer. Production-app startup enforcement is still
  not wired.
- **RT-4 (partial)**: the sandbox executed the loader, PRoot and OpenCode from
  the native-library directory with no `execute_no_trans` denials. The
  production-app E2E suite is still absent. The logcat scan found 25
  MediaTek `ioctl` denials on `/proc/perfmgr/perf_ioctl` (vendor
  performance hints), not file/exec denials.
- **RT-5 (confirmation)**: on-device PT_LOAD alignment 65536 B / 16384 B.
- **TERM-2/TERM-3 (partial)**: native `forkpty`, 80x24 window size and
  environment parity, plus `--kill-on-exit` cleanup. These do not test the
  production terminal service.
- **TERM-5 (partial)**: scripted native-PTY byte round trip, including
  signals. Gboard and the production terminal UI remain untested.
- **SEC-1 (partial)**: authenticated loopback from an app sandbox works. No
  second-UID client and no unauthenticated-rejection matrix were run from the
  harness.
- **SEC-2 (partial)**: no execute denials and no secret material in the
  report. The production APK scan remains the authoritative check.

## Reproduce

```bash
python3 scripts/stage-spike-harness.py
./gradlew -PspikeHarness=true :spike-harness:assembleDebug
adb install -r tests/spikes/harness/build/outputs/apk/debug/spike-harness-debug.apk
# Tap "Download and verify pinned runtime", then
# "Run all available device probes (S1/S3)", then "Write JSON report".
adb shell run-as io.github.spectordev9.opencode.spike.debug \
  cat files/reports/spike-*.json
```
