# S7 child-process survival smoke — 2026-10-25, staged runtime APK

> Historical record. v1 G0 uses the Infinix X6870 only; references below to
> Tier A/B/C, 16 KB-page devices, Samsung Keyboard, 30-minute/two-device runs
> or 19/20 repeats are superseded by `docs/spec.md` and `docs/plan/phase-0.md`.

Raw report: [`raw/spike-s7-smoke-20261025-111834.json`](raw/spike-s7-smoke-20261025-111834.json)
Guest sample log: [`raw/s7-smoke-20261025-child-log.txt`](raw/s7-smoke-20261025-child-log.txt)

Status: **feasibility evidence only; this entry does not change any row in
`docs/evidence/RESULTS.md` and does not close LC-2/S7 acceptance.**

- Report SHA-256: `1bd3413640409e946c255386261a1c59ef061dde632f329992badab5593b7590` (verify before reuse)
- Guest log SHA-256: `8306a922159982e4ce103be369cf35df12c161696c8204367212b9c788e2ed00`
- Device: Infinix X6870, Android 16 (SDK 36), arm64-v8a, 4 KB pages
- APK: `spike-harness-debug.apk` 0.1.0-spike, SHA-256
  `82ab830b729c1bd8e5b832201c4f2bdf2746b1b2c32563ab65592c9b4a1cfa85`,
  `extractNativeLibs=true`; PTY probe and `libldmusl.so`, `libproot.so`,
  `libproot_loader.so` packaged in `nativeLibraryDir`
- Runtime: `runtime/artifacts/spike-runtime.tar.gz` SHA-256
  `e5d9c0af22dc678e0c908964fcf3a17f0eee9828256d3083cfecb94b6fa54812`,
  staged on device by `run-as` tar extraction; the harness
  "Refresh device and runtime" screen reported all ten required entries OK,
  including the recreated Alpine symlinks (`bin/sh -> /bin/busybox`).
  This also exercises the fixed Windows staging pipeline end to end.

## What ran

A 1-minute smoke soak of the new S7 child-process probe (the service accepts
1–120 minutes; the smoke uses the minimum):

```bash
adb shell run-as io.github.spectordev9.opencode.spike.debug \
  /system/bin/am start-foreground-service --user 0 \
  -n io.github.spectordev9.opencode.spike.debug/io.github.spectordev9.opencode.spike.SpikeForegroundService \
  -a io.github.spectordev9.opencode.spike.START_S7_SOAK --ei soak-minutes 1
```

The service started a real PRoot/Alpine guest child
(`libproot.so --kill-on-exit -0 -r …/rootfs -b /dev -b /proc -b /sys -w /root
/bin/sh -c '<sample loop>'`; full command in the report), supervised it from
the service heartbeat, and analyzed the guest sample file at soak end.

## Records in the report

| Record | pass | key detail |
| --- | --- | --- |
| `S7-child-process-start` | true | PRoot child started, sample interval 10 000 ms |
| `S7-child-process-survival` | true | `samples=7`, `expectedSamples=5`, `coverage=1.40`, `maxSampleGapMs=11000`, `gapsAboveThreshold=0`, `indexAnomalies=0`, `childAliveAtStop=true`, `hostChecks=1 hostAlive=1 hostDead=0`, `timebase=wall-clock` |
| `S7-background-survival` | true | `elapsedRealtimeMs=60010`, `serviceRestarts=0`, `partialWakeLockAcquired=true`, thermal status 0 (below moderate) |

Guest samples were written every 10 s as `<wall-epoch-s> <kernel-uptime-s>
<index>`. The guest log shows `uptime=0` on every line: under this PRoot
configuration the guest cannot read `/proc/uptime`, and the probe's documented
wall-clock fallback was used (`timebase=wall-clock`).

## Observed: teardown did not terminate the guest

The smoke exposed a harness defect that is independent of the survival
measurement:

- `S7-child-process-survival.durationMs=69999` — the analysis consumed the
  two 5 s `waitFor` timeouts, i.e. neither `Process.destroy()` nor
  `destroyForcibly()` ended the PRoot guest on this device.
- The guest kept appending samples: 31 samples, indices 1..31 with no gaps
  (10–11 s intervals, span 303 s, 11:11:48–11:16:51). **24 of them were
  written after the service was destroyed at 11:12:58.**
- `ps` still showed `libproot.so` + guest `sh` minutes later; both were
  stopped immediately by an external `adb shell run-as <pkg> kill -9 <pid>`.

Consequences: the survival record is valid for the soak window it measured
(and the child, in fact, outlived the soak by ~4 minutes), but the harness can
leave the PRoot guest running after a soak ends. Root cause of the ineffective
destroy is not yet established; the README records the workaround. A follow-up
should kill the process group and log teardown failures instead of swallowing
them.

## Follow-up: teardown fix (2026-10-25)

The teardown defect above is fixed in the harness source: the tracer PID is
captured from `/proc` at start (the Android SDK exposes neither
`java.lang.ProcessHandle` nor `Process.pid()`), descendants are enumerated
through `/proc` parent links before the tracer is signalled, and survivors are
killed with SIGKILL and verified; failures are logged instead of swallowed.
The fix compiles into APK SHA-256
`ecc9b0358cf9471b08aa727fbe9b224de2ce6771841ef19d35573dadf3539c0`. Its
on-device teardown behavior has not been re-observed yet — this entry remains
the historical record of the leak. See
[`harness-defects-20261025.md`](harness-defects-20261025.md).

## Not covered

The LC-2/S7 acceptance run remains 30 minutes with real agent work and the
19/20 per-tier survival requirement from `docs/evidence/RESULTS.md`. This
smoke ran no agent task and must not be cited as gate evidence.
