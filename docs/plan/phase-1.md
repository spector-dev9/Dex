# Phase 1 plan — backend foundation (G1)

Status: **In progress.** The owner directed G1-G2 implementation to begin on
2026-10-27 while G0 retains open blockers. G0 is not green; its open items
remain release blockers and must not be reported as accepted.

G1 is complete only when RT-1 through RT-4, SEC-1, SEC-2, STR-1, STR-5,
LC-3, WS-1 and WS-2 pass headless on the Infinix X6870. RT-5 is out of scope
for v1. This phase builds production backend composition; it does not build
the final feature UI.

## Carried G0 blockers

- S1-B authenticated server/prompt evidence and S2 Profile A/B device
  measurements remain open.
- S3 production agent-shell comparison remains open.
- S6 has no distributable OpenCode 2.x artifact; keep the 1.19.3 pin.
- S9 Bun/libtalloc source-offer and dependency-closure work remains open.
- D4 remains provisional until its evidence clears.

These do not pause G1, but no release claim may treat them as resolved.

## Milestones

### M0 — composition and bridge surface

- [x] Injectable production bridge handler for runtime, workspace, Git and
      terminal commands, with app unit coverage against service fakes.
- [x] Add envelope/result JSON serialization with app unit tests.
- [x] Add an in-process typed bridge transport with app unit tests.
- [ ] Add per-command wire DTOs and a JSON bridge transport binding.
- [ ] Add the app composition root that constructs real service ports.
- [~] Route all 17 session commands through the OpenCode client; settings
      command handling remains pending a frozen settings contract.
- [ ] Keep the test fake services separate from production wiring.

Exit: the bridge handler covers every command needed by G1 and can be
constructed with real or fake ports without changing call sites.

### M1 — runtime install and launch

- [~] Make runtime installation atomic: archive and executable hashes and
      staging/atomic move are implemented; fsync durability remains open.
- [~] Verify extracted files and the pinned version at startup; executable
      digest and manifest layout are checked, but full extracted-tree
      verification remains open.
- [ ] Implement the Android process launcher using packaged native
      entrypoints; do not execute from writable storage.
- [ ] Wire the pinned manifest and runtime assets into the app build.
- [ ] Add RT-3 integrity/version tests and RT-4 write-exec denial checks.
- [ ] Measure RT-1 offline health and RT-2 cold/first-run/warm startup on
      the Infinix X6870.

Exit: the app can install, verify, start and stop the pinned runtime
headlessly, with hash and version mismatches refused.

### M2 — server supervision and lifecycle

- [~] Implement server start, health, stop and restart with the
      `SupervisorConfig` timeouts and backoff; JVM supervisor and tests are
      implemented, production launcher and authenticated health remain open.
- [ ] Add exactly-one-listener enforcement and process-tree termination.
- [~] Implement restart backoff and the crash-loop breaker (five failures in
      five minutes by default); JVM implementation and tests are in place.
- [ ] Implement SIGKILL recovery for LC-3: healthy within 10 s p95, sessions
      intact, exactly one listener.
- [ ] Add foreground-service and wake-lock ownership only while an agent task
      is active; release both when idle.
- [~] Record supervision diagnostics without secrets; the in-memory
      diagnostics service scrubs events, but supervisor wiring remains open.

Exit: LC-3 passes on device and the supervisor reports stable state through
the bridge.

### M3 — gateway, authentication and secrets

- [ ] Compose `OpenCodeHttpClient` with an ephemeral loopback listener and a
      per-launch password provisioned through `SecretStore`.
- [ ] Authenticate health, SSE and every protected route with Basic auth.
- [ ] Implement SSE reconnect/resync from the last event ID and authoritative
      state reconciliation for STR-5.
- [ ] Prove STR-1: no periodic message-list polling while connected.
- [ ] Prove SEC-1 from a second-UID app: every documented route rejects
      requests without the secret; only expected listeners exist.
- [ ] Prove SEC-2: scan APK, logcat, app logs, crash dumps and process
      arguments; zero secret findings; restrictive credential permissions.
- [~] Add production log scrubbing and diagnostics redaction; the in-memory
      service applies `LogScrubber`, but the production scrubber remains open.

Exit: STR-1, STR-5, SEC-1 and SEC-2 pass on device using the production
gateway and secret path.

### M4 — workspace device acceptance

- [ ] Wire `FileWorkspaceService` through the composition root using the
      app-private workspace default.
- [ ] Run the WS-1 fuzz corpus on device: hostile paths, absolute paths,
      symlink escapes and loops, NUL, unicode normalization and case variants.
- [ ] Run WS-2 SIGKILL interruption tests on the target filesystem.
- [ ] Measure workspace latency and memory budgets on the reference device.
- [ ] Validate create, rename, move, trash and restore flows, including
      spaces, quotes, unicode and empty files.

Exit: WS-1 and WS-2 pass on the Infinix X6870 with zero escapes and no
partially written files.

### M5 — G1 acceptance and evidence

- [ ] Add a headless device test entry point that runs the G1 gates without
      depending on the final G2 UI.
- [ ] Record APK/runtime hashes, device/build identity, commands, timings and
      logs for each gate.
- [ ] Update `docs/traceability.md` and `docs/evidence/RESULTS.md` with
      implementation and evidence links.
- [ ] Keep owner overrides explicit; never promote G1 rows from host tests
      alone.

Exit: every G1 row has passing device evidence on the exact candidate
hashes, or remains explicitly open.

## Risks

- The 1.19.3 runtime is musl-linked and needs packaged loader/PRoot support;
  the executable must not rely on writable-storage exec.
- Android background execution may truncate child sampling; lifecycle claims
  must separate service survival, child survival and active-agent completion.
- The gateway and supervisor must share one authenticated endpoint and one
  credential source; split lifecycles would invalidate SEC/LC evidence.
- Runtime asset staging increases APK size and CI time; keep the debug harness
  separate from the production build.
