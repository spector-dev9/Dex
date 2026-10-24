# Phase 2 device spike harness (isolated, pre-G0)

This is a disposable debug app for the G0 device evidence described in
`docs/plan/phase-0.md`. It is **not production wiring** and is excluded
from normal builds. It must only be included with
`-PspikeHarness=true`.

The harness currently covers:

- device metadata needed by the evidence matrix (SDK, ABI, page size,
  native-library directory, `extractNativeLibs`);
- ELF entrypoint checks for the bundled musl loader, PRoot and PRoot's
  loader, including PT_LOAD alignment against the 16 KB rule;
- S1 app-sandbox launch of `opencode --version` through the bundled
  loader, with the binary and libraries in app-private storage;
- S1 partial: authenticated loopback server health and a deterministic
  OpenAI-compatible mock-provider prompt round trip;
- a one-tap runner for the available S1/S3 entrypoint, loader, server,
  guest-shell, and native-PTY probes;
- S3 partial: a PRoot/Alpine guest shell (`pwd`, `PATH`,
  `node -v`, `/etc/alpine-release`), plus native `forkpty` comparison
  of guest `pwd`, `PATH` and `node -v`;
- S7 partial: a single 30-minute `specialUse` foreground-service soak,
  with persisted heartbeats and thermal-status samples; it is not the
  agent-task 19/20 acceptance test;
- S8 partial: keyboard/IME input observation in a text field using
  `InputConnection`, `TextWatcher` and hardware-key capture; this is not
  a native terminal and does not replace the PTY comparison;
- a JSON report with command lines, exit codes and bounded stdout/stderr.
  The report also includes the installed APK SHA-256, device memory/page
  size, default IME, battery-optimization state, and thermal status.

It does **not** include production OpenCode server/service integration, the
supervisor, an SSE gateway, Git or workspace services. Those remain deferred
until after G0. Do not treat a passing harness run as device acceptance:
the authoritative matrix is `docs/evidence/RESULTS.md`.

## Build and install

```bash
python3 scripts/stage-spike-harness.py
./gradlew -PspikeHarness=true :spike-harness:assembleDebug
adb install -r tests/spikes/harness/build/outputs/apk/debug/spike-harness-debug.apk
```

The staging script downloads the official OpenCode release, pinned
Alpine rootfs/runtime packages, Node.js 20.15.1 for arm64/musl and its
runtime dependencies, and Android libraries with
`scripts/fetch-runtime.py --alpine --android-libs --alpine-runtime-libs --alpine-node`,
verifies their hashes, creates `runtime/artifacts/spike-runtime.tar.gz`, and copies
`libldmusl.so`, `libproot.so` and `libproot_loader.so` into the
gitignored `src/main/jniLibs/arm64-v8a/` directory. It also stages
PRoot's helper libraries as APK assets. Re-run the Gradle build after
staging so the APK contains the native entrypoints and helper libraries.
The harness sets `PROOT_LOADER` to the packaged PRoot loader; without it
PRoot would look for the Termux `libexec` path.

At runtime, tap **Download and verify pinned runtime**. The harness reads the
same `runtime/manifest.json` and downloads the pinned OpenCode archive, Alpine
minirootfs, Alpine runtime packages, Node.js package and its pinned dependency
packages over HTTPS concurrently (up to four at a time). It verifies each
SHA-256 before installation, installs the Node package set into the guest
rootfs, and stores downloaded archives in app-private storage. The OpenCode
executable and rootfs are installed using the core runtime installers. The
first download is large; progress and failures are shown in the harness.
PRoot entrypoints remain packaged native libraries and are never downloaded
or executed from writable app storage.

## Two-device test run

Install the same staged APK on both devices and retain the exported report
from each. Each report records the APK SHA-256 and device metadata so the
results can be compared without conflating different builds or device tiers.

On each device:

1. Tap **Download and verify pinned runtime** and wait for success.
2. Tap **Run all available device probes (S1/S3)**. This runs packaging,
   loader/version, authenticated mock prompt, guest shell and native-PTY
   checks in sequence. Inspect each result; the summary is not a G0 verdict.
3. For S7, tap **Start S7 30-minute background survival run**, background
   the app and turn the screen off for at least 30 minutes, then reopen it
   and tap **Finish S7 run and record survival**. The exported record
   includes heartbeat freshness and thermal samples. This tests only the
   harness foreground service, not an agent task or the required 19/20
   repeated-run acceptance threshold.
4. For S8, run the keyboard observation separately with Gboard and Samsung
   Keyboard where available. Try the extra-key row, hardware keys, paste and
   text selection, then tap **Record this keyboard's observation** before
   switching IMEs. The report captures a separate IME identity and observed
   input record for each run but intentionally does not mark keyboard
   acceptance as passed. Soft keyboards normally deliver text through
   `InputConnection.commitText`/`setComposingText`, not `KeyEvent`, so a
   zero hardware `keyDownEvents` count is expected and is not a failure.
5. Tap **Write JSON report** and provide the JSON from both devices.

This APK can collect only the device-side feasibility probes listed above.
Profile A/B performance comparison, host SSE/contract checks, OpenCode 2.x
compatibility, full release compliance, and production-app acceptance are
not implemented by this isolated harness and remain explicit blockers in
the JSON report.

For the S1 server probe, tap **Start authenticated server + mock prompt**.
It starts OpenCode directly through the bundled musl loader, binds the
server and deterministic mock provider to `127.0.0.1`, checks authenticated
`/global/health`, creates a session, and polls for the exact mock response.
The probe uses a per-run random server password and a test-only provider key;
it never contacts a hosted model. Cleartext networking is enabled only in
this isolated debug harness so its loopback HTTP server and mock provider can
communicate. This is not production listener or gateway wiring.
Each startup/prompt stage is appended to
`Downloads/OpenCodeSpike/s1-server-diagnostics.log` and tagged
`SpikeHarness` in logcat, so it is visible outside the app and remains
available if the probe process exits before a report is written. Retrieve
it from the device's Downloads folder, or with:

```bash
adb pull /sdcard/Download/OpenCodeSpike/s1-server-diagnostics.log
adb logcat -d -s SpikeHarness AndroidRuntime
```

The harness builds a test-only `arm64-v8a` JNI helper with NDK 27.2 and
CMake 3.22.1. It calls `forkpty`, starts the same PRoot/Alpine shell used
by the guest-shell probe, sets an 80x24 terminal, and compares the
reported guest working directory, `PATH` and successful `node -v` result.
The report uses `D=`, `P=` and `N=` markers; missing fields or a failed
Node invocation fail the probe instead of being treated as an environment
match (`D` is `pwd`, `P` is `PATH`, and `N` is the `node -v` output).
The harness installs the pinned Alpine Node.js 20.15.1 arm64/musl package
and its dependencies into the guest before running S3. This is feasibility
evidence only, not the production `PtyService` implementation.

CI compiles the harness **without** running the staging script, so the
CI APK is a compile check. It can fetch the OpenCode/Alpine runtime, but
cannot run S1/S3 because the native loader and PRoot entrypoints are not
present in the APK.

The rootfs entrypoint check resolves absolute symlinks under the guest
root, so Alpine's `/bin/sh -> /bin/busybox` link does not need to be
rewritten on the device.

When writing the JSON report, the harness includes an `S8-ime-observation`
result with text-change count, final text length, hardware-key count,
`InputConnection` counters (`commitText`, `setComposingText`,
`deleteSurroundingText`, `sendKeyEvent`, editor actions and content
commits), selection/context-menu counts, the last observed event and the
default IME package/class. A pass means input was observed; keyboard
acceptance is explicitly not assessed. Hardware `keyDownEvents=0` is
normal for soft keyboards and must not be treated as a failure. Typed text
itself is not included. S9 is release-compliance evidence and is not an
on-device probe.

Manual staging is only needed when testing an older harness build without
the runtime download button:

```bash
adb push runtime/artifacts/spike-runtime.tar.gz /data/local/tmp/
adb shell run-as io.github.spectordev9.opencode.spike.debug \
  sh -c 'cd files && tar xzf /data/local/tmp/spike-runtime.tar.gz'
```

Then open **OpenCode Spike Harness**, press **Refresh**, and run the
probes. The report is written to `files/reports/` and the app-external
`Android/data/io.github.spectordev9.opencode.spike.debug/files/reports/`,
and exported as `Download/OpenCodeSpike/spike-<timestamp>.json` so it is
visible in the device's Downloads folder. Android 10 and later use the
Downloads media collection; Android 9 and earlier request storage permission
when exporting. Tap **Write JSON report** after running the probes.

```bash
adb shell run-as io.github.spectordev9.opencode.spike.debug ls files/reports
adb shell run-as io.github.spectordev9.opencode.spike.debug cat files/reports/<report>.json
adb logcat -d -s SpikeHarness
```

## Evidence to attach per device

Record the device tier, Android release, ABI, page size
(`getconf PAGE_SIZE` or the harness report), the exact APK and runtime
hashes, and whether the loader/PRoot came from `jniLibs`. Attach:

1. the JSON report;
2. `adb logcat -d -s SpikeHarness`;
3. `adb shell dumpsys package io.github.spectordev9.opencode.spike.debug`
   (confirms `extractNativeLibs`);
4. for S7, the start/stop timestamps and a 30-minute background survival
   result;
5. for S8, the keyboard model and the IME text/key trace.

## Known limitations

- PRoot is staged as `libproot.so` and its loader as
  `libproot_loader.so`; `libtalloc.so.2` and `libandroid-shmem.so` live
  in app-private storage and are exposed through `LD_LIBRARY_PATH`. The
  probe reports linker failures; fixing the packaging or rebuilding
  PRoot statically is part of the D3 decision, not this harness.
- The official OpenCode tarball contains only the `opencode` binary.
  Alpine supplies musl; pinned `libgcc` and `libstdc++` APKs supply the
  other ELF libraries.
- `opencode` is mapped by the musl loader from app-private storage; this
  is allowed precisely because the loader itself is an entrypoint from
  `nativeLibraryDir`. A direct `execve` of the opencode binary is not
  attempted.
- The harness clears `LD_PRELOAD` for child processes. Termux exports
  `libtermux-exec-ld-preload.so`, which breaks the guest musl loader;
  device app processes normally do not set it, but the harness should not
  inherit it.
- The harness is debug-only (`run-as` is required for staging) and uses
  `minSdk 26` so it can run on older test devices; production minSdk is
  decided at G0.
