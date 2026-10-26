# Harness failure log — 2026-10-25

> Historical record. v1 G0 uses the Infinix X6870 only; references below to
> Tier A/B/C, 16 KB-page devices, Samsung Keyboard, 30-minute/two-device runs
> or 19/20 repeats are superseded by `docs/spec.md` and `docs/plan/phase-0.md`.

Status: working failure record, not gate evidence. It records what failed
during the 2026-10-25 S1/S3/S7 harness work, why it failed, what was fixed,
and what remains unverified. Raw artifacts are under [`raw/`](raw/).

## 1. S1 probe crash — fixed and verified

- **Symptom:** the first S1/S3 suite attempt died after S1 with
  `FATAL EXCEPTION: s1-server-output`
  (`java.io.InterruptedIOException: read interrupted by close() on another
  thread`, `SpikeServerProbe.kt:126`). Android recorded
  `reason=4 (APP CRASH(EXCEPTION))` and all in-memory records were lost.
- **Root cause:** `stopProcess()` calls `Process.destroy()`, which closes the
  process streams from another thread. The drain thread's blocking
  `InputStream.read()` throws `InterruptedIOException`; it was uncaught, so it
  killed the harness process.
- **Fix:** the drain thread now catches teardown exceptions, matching the
  existing `SpikeProbe.drain` pattern.
- **Verification:** rebuilt APK
  `733f7f7916c6a9f796244563dd1918dd86fd12d49d8e93333afb0fa355f2304a`; the
  rerun passed 10/10 available S1/S3 probes, the diagnostics end with
  `probe-shutdown`, no new `harness-crash-*` file was produced, and the
  post-fix exit-info snapshot has no crash entries. Evidence:
  [`S1-S3-device-suite-20261025.md`](S1-S3-device-suite-20261025.md),
  `raw/harness-crash-20261025-112811.txt`,
  `raw/harness-exit-info-after-20261025.txt`.

## 2. S7 PRoot guest survived teardown — fixed in source, not re-observed

- **Symptom:** in the 1-minute smoke, `S7-child-process-survival.durationMs`
  was 69 999 ms (both 5 s waits consumed) and the guest kept appending
  samples after service teardown: 24 of 31 samples were written after
  11:12:58, and `libproot.so` plus the guest `sh` were still present minutes
  later. They were stopped externally with
  `adb shell run-as <pkg> kill -9 <pid>`. Evidence:
  [`S7-smoke-20261025-child-probe.md`](S7-smoke-20261025-child-probe.md).
- **Root cause:** `Process.destroy()`/`destroyForcibly()` signal only the
  direct child — the PRoot tracer. The guest workload runs as the tracer's
  traced descendants, and PRoot's `--kill-on-exit` cleanup cannot run when
  the tracer is killed uncatchably, so the guest survives and keeps sampling.
- **Fix:** `SpikeChildProcessProbe` captures the tracer PID at start from
  `/proc` (the Android SDK exposes neither `java.lang.ProcessHandle` nor
  `Process.pid()`), walks `/proc` parent links for descendants before
  signalling the tracer, sends SIGKILL to every survivor, verifies each is
  gone, and logs teardown failures instead of swallowing them. Both
  `abort()` (service teardown) and `stopAndAnalyze()` use the same path.
- **Verification status:** compiles into APK
  `ecc9b0358cf9471b08aa727fbe9b224de2ce6771841ef19d35573dadf3539c0`. On-device
  teardown behavior has **not** been re-observed: the owner treats S7 as
  green and instructed not to run S7, so no new smoke or soak was started.
  Until a run confirms it, check for a leaked guest after any soak and stop
  it with `adb shell run-as <package> kill -9 <pid>`. The old workaround note
  in the harness README is replaced by this status.

## 3. Build/tooling failures met while fixing teardown

- `java.lang.ProcessHandle` is not in `android.jar` (SDK 36), and
  `java.lang.Process.pid()` is not part of the Android SDK surface either.
  Two builds failed: `Unresolved reference 'ProcessHandle'` /
  `Unresolved reference 'toHandle'`, then `Unresolved reference 'pid'`. A
  `javap` run against the JDK's `java.lang.Process` misleadingly showed those
  members; the JDK class is not the harness compile classpath.
- Resolution: no hidden APIs. The tracer PID is resolved at launch from
  `/proc` parent links (`/proc/<pid>/stat` field 4), and descendants are
  enumerated the same way. The guest runs under the app UID, so its `/proc`
  entries are readable by the harness.
- The Windows staging pipeline also failed earlier on link-heavy Alpine
  archives: `TarFile.extractall` resolving links recursively raised
  `RecursionError`, and creating symbolic links needs a Windows privilege.
  The staging script now extracts member by member, records symlink targets
  and permission bits, and recreates them in the device archive. The S7
  smoke report's "Refresh device and runtime" screen confirms the recreated
  `bin/sh -> /bin/busybox` link.

## 4. Still open (G0 blockers, unchanged by these fixes)

These are not harness defects; they are missing evidence or scope items:

- Tier A/B/C baselines: only one Tier A-class device (Infinix X6870, Android
  16, 4 KB pages) is attached; no Tier B, Tier C or 16 KB-page device.
- S6 remains blocked: the newest upstream release and npm `latest` are still
  1.19.4; no 2.x artifact exists. The 1.19.3 pin stays.
- The TERM-5 PTY probe, LC-5/LC-6, the Samsung Keyboard field test and the S9
  legal items remain open; see `docs/plan/phase-0.md` and
  `docs/project-checklist.md`.
- The 30-minute, 19/20 active-agent S7 acceptance run has not been performed;
  the smoke is feasibility evidence only.

G0 is therefore still not claimable under `docs/spec.md` line 316.
