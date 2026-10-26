# S1/S3 device suite — 2026-10-25, fixed harness APK (Infinix X6870)

Raw report: [`raw/spike-device-suite-20261025-113641.json`](raw/spike-device-suite-20261025-113641.json)
· SHA-256 `74fcff8edc48e3b14a9e5c44fe76c79c86aea7d1f8320c4bd515e011e4891dc0`
(byte-identical copies pulled from `files/reports` and
`Download/OpenCodeSpike`)

S1 run diagnostics: [`raw/s1-server-diagnostics-20261025.log`](raw/s1-server-diagnostics-20261025.log)
· SHA-256 `dab166e73cde3abbaa12ee081929e44979bdbcfebd848e54d95d2ab8d9548633`
(this file appends both the pre-fix run and the fixed rerun)

Crash from the pre-fix attempt:
[`raw/harness-crash-20261025-112811.txt`](raw/harness-crash-20261025-112811.txt)
· SHA-256 `3038c4a53f3f35c917a2c67e2bcc0f732e3720257541ad55656442a267507c94`

Exit-info snapshots (pre-fix and post-fix):
[`raw/harness-exit-info-20261025.txt`](raw/harness-exit-info-20261025.txt)
sha `0864e70190e198f3d4dd620ee3c4726a246682d0c7af4b8ad49cfed7544ece0f`,
[`raw/harness-exit-info-after-20261025.txt`](raw/harness-exit-info-after-20261025.txt)
sha `a3cffa84a792f366f45b543a29dc8444fed9d53c9c0308efb09d24068616bd86`

Status: **pre-G0 isolated-harness evidence. This entry does not change any
row in `docs/evidence/RESULTS.md`; it does not establish the Tier A/B/C
device matrix or G0.**

- Device: Infinix X6870, Android 16 (SDK 36), arm64-v8a, 4 KB pages,
  ~5.7 GB RAM
- APK: `spike-harness-debug.apk` 0.1.0-spike, SHA-256
  `733f7f7916c6a9f796244563dd1918dd86fd12d49d8e93333afb0fa355f2304a`,
  `extractNativeLibs=true`
- Report written by the harness UI 2026-10-25T11:36:41+05:30 after
  "Run all available device probes (S1/S3)" then "Write JSON report".

## Result — 10/10 available S1/S3 probes passed

| Record | pass | key detail |
| --- | --- | --- |
| S1-native-loader | true | `libldmusl.so` executable in `nativeLibraryDir`, `minLoadAlign=65536` (16 KB-ok) |
| S3-native-proot | true | `libproot.so`, `minLoadAlign=16384` (16 KB-ok) |
| S3-native-proot-loader | true | `libproot_loader.so`, `minLoadAlign=16384` (16 KB-ok) |
| runtime-layout | true | staged runtime layout complete under `files/spike` |
| S1-loader | true | `opencode --version` through the musl loader, exit=0 (2,582 ms) |
| S1-server-health | true | authenticated `/global/health` HTTP 200 (4,349 ms) |
| S1-mock-provider-prompt | true | mock prompt round trip, provider requests=1 (5,080 ms) |
| S3-guest-shell | true | exit=0 (507 ms) — standalone guest-shell probe |
| S3-guest-shell | true | exit=0 (155 ms) — guest side re-run inside the PTY comparison |
| S3-native-pty | true | exit=0, `terminalSize=80x24`, `environmentMatch=true` (437 ms) |
| device-suite-summary | true | 10/10 passed in 13,457 ms |

The report also carries one `S7-background-survival` record: the persisted
result of the earlier 1-minute smoke soak, folded in at export time because
"Write JSON report" completes any finished soak state. It is labeled as a
1-minute harness diagnostic; see
[`S7-smoke-20261025-child-probe.md`](S7-smoke-20261025-child-probe.md).

## The pre-fix attempt crashed (fixed before this run)

- First suite attempt (11:27): S1 already passed
  (`mock-prompt-passed`, provider requests=1), then the app died with
  `FATAL EXCEPTION: s1-server-output`
  (`java.io.InterruptedIOException: read interrupted by close() on another
  thread`, `SpikeServerProbe.kt:126`). `stopProcess()`'s `destroy()` closes
  the process streams from another thread; the drain thread did not catch
  that expected exception, so it killed the harness process and all
  in-memory records were lost. Android recorded
  `reason=4 (APP CRASH(EXCEPTION))` and killed the package at 11:28:17
  (see the crash and exit-info files above).
- Fix: the drain thread now swallows teardown exceptions (the same pattern
  as `SpikeProbe.drain`). Rebuilt APK `733f7f79…`; the rerun completed
  10/10 with a clean `probe-shutdown` in the diagnostics, no new
  `harness-crash-*` file, and no crash entries in the post-fix exit-info
  snapshot.
- After the fixed run no `opencode`/`libldmusl`/PRoot processes remained
  under the app UID: teardown of the S1 server process works. (The
  `reason=16 (PACKAGE UPDATED)` entry at 11:33:36 is the expected stop of
  the old `:nativepty` process during `adb install -r`.)

## Instrumental detail

- The `minLoadAlign` checks are ELF-side 16 KB-page compatibility signals
  for the three packaged entrypoints. They do not substitute for the RT-5
  requirement of exercising an actual 16 KB-page device (none is attached).
- The report's `knownNotCovered` field lists everything this run does not
  cover: Tier A/B/C matrix and Profile A/B comparison, S2, S4/S5, S6 2.x,
  the S7 19/20 agent-task acceptance, TERM-5 keyboard acceptance, S9, and
  production UI/security/thermal rows.
