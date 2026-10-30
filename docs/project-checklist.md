# Project Checklist

This checklist consolidates the project plan, threat model, traceability map,
and current Phase 0/device-gate status. It is a working index, not a replacement
for those source documents or their detailed acceptance criteria.

Status: `[x]` complete/green, `[~]` partial/in progress, `[ ]` open. Each
reported result identifies whether it is host, harness, or device evidence. A
passing isolated probe does not automatically promote a broader G0 acceptance
gate. G0 runs on the owner's **Infinix X6870 only**; Tier A/B/C, 16 KB-page
devices, Samsung Keyboard and other hardware are out of scope for v1.

Run the repository's automatable G0 checks with `python3 scripts/run-g0-suite.py`;
use `--host-only` for host checks or `--list` to inspect the suite. The runner
reports physical-device, unavailable-artifact, and owner-decision gates
separately. Passing automated checks alone does not unblock G0.

## Current Test Status

- [x] Gated spike APK assembles in CI; the latest inspected device report
  used APK SHA-256
  `f63f14ad18b4ad8b013e863b247ffce78be04466e33f121c0bbbe30550e3753c`
  (2026-10-30).
- [x] S1 authenticated-health and mock-provider smoke: two user-reported runs
  passed; see `tests/spikes/results/S1-server-mock-20261024.md`.
- [x] Record the fixed-harness S1/S3 device suite: 10/10 available probes
  passed on the Infinix X6870. Owner-accepted 2026-10-27 as non-blocking
  harness evidence for G0; production S1/S3 remain post-G0 follow-ups. See
  `tests/spikes/results/S1-S3-device-suite-20261025.md`.
- [x] Harness exposes one button per G0 gate. Available probes run; gates that
  require production wiring, external artifacts, or owner decisions record
  blocked/manual/deferred status instead of a false pass.

## 1. Decisions and project gates

- [x] Keep the native Kotlin/Compose direction and staged migration recorded.
- [x] Record GitHub Releases + Obtainium first, self-hosted F-Droid after
  reproducible builds, and defer Play; see `docs/adr/0001-distribution.md`.
- [x] Keep OpenCode 1.19.3 as the current exact baseline; verify its manifest
  hash and refuse unknown major versions.
- [ ] Complete G0: run S1-S6 and S9 on the Infinix X6870, record its
  baselines, ratify provisional thresholds, and freeze the decisions
  governed by `docs/spec.md`.
- [ ] Resolve D4 runtime profile using the documented S1/S3/S2 decision rules;
  do not encode a default before the evidence is admissible.
- [ ] Resolve the S6 2.x artifact check when a distributable artifact exists;
  do not change the current 1.19.3 pin based on an unavailable artifact.
- [~] Owner direction (2026-10-27): proceed with G1-G2 implementation while
  the open G0 blockers remain release blockers. G1 work is tracked in
  `docs/plan/phase-1.md`; G2 in `docs/plan/phase-2.md`.
- [ ] Resolve discrepancies between the legacy root `plan.md` decision IDs and
  `docs/spec.md`; use the latter for G0 decisions and avoid conflating IDs.

## 2. Contracts, architecture, and migration

- [x] Pin the OpenCode 1.19.3 OpenAPI snapshot and generate/check the endpoint
  inventory and contract drift.
- [x] Keep TypeScript/Kotlin bridge parity and architecture checks in CI.
- [x] Keep service API modules free of Android imports and enforce dependency
  direction with the architecture checker.
- [x] Preserve host tests for the bridge parser, reconnect/resync logic,
  runtime artifacts, workspace guards, and bounded file reads.
- [~] G1 implementation started: an injectable app bridge handler now covers
  runtime, workspace, Git and terminal commands. Runtime launcher, supervisor,
  gateway, diagnostics and payload DTOs remain open.
- [ ] Complete G1 device acceptance: RT-1..RT-4, SEC-1/2, STR-1/5, LC-3, and
  WS-1/2 as specified in `docs/plan/phase-1.md`. RT-5 is out of scope for v1.
- [ ] Keep test fakes and test-only harnesses clearly separate from production
  composition and device-acceptance evidence.
- [ ] Finish the strangler migration in order: models/client, runtime,
  workspace/security/terminal, then retire legacy paths.
- [ ] Maintain the `upstream` remote and record adaptations in `docs/UPSTREAM.md`.

## 3. Runtime, build, and lifecycle

- [~] Manifest-driven, HTTPS-only, bounded artifact downloads and digest
  verification have host coverage; finish packaged-runtime validation.
- [ ] Validate cold install, cache reuse, interrupted retry, hash rejection,
  native PRoot packaging, app-sandbox entrypoints, and production launch.
- [ ] Complete runtime supervision: authenticated health probes, restart
  backoff, crash-loop degradation, process-tree termination, and diagnostics.
- [ ] Verify the pinned runtime and native helpers from `jniLibs` on target
  devices; copied writable-storage executables are not admissible for D3.
- [ ] Complete the host build setup: provide NDK 27.2.12479018 and an arm64
  SDK `aapt2` override, or use the configured Android CI build; verify the
  staged APK rather than treating Gradle configuration as a passing build.
- [ ] Run app assembly, unit tests, lint, and the spike-harness build in the
  supported CI/toolchain environment.
- [ ] Implement production lifecycle policy: foreground service and
  `PARTIAL_WAKE_LOCK` only while an agent task is active; release both when
  idle and shut down cleanly.
- [ ] Explain battery-optimization exemption requests and document OEM
  autostart/background controls, including Infinix and Nothing.
- [ ] On resume, health-check the process, reconnect SSE from the last event
  ID, and re-fetch authoritative session state.
- [ ] Test Android 12+ phantom-process-killer behavior for PRoot/Node children
  separately from foreground-service survival.
- [ ] Keep the app-private workspace default; gate external/all-files access
  behind an explainer and runtime restart, with SAF-only behavior where required.

## 4. OpenCode client, UI, and session behavior

- [x] Keep the authenticated HTTP/SSE client, typed contract, bounded JSON
  reads, streaming SSE, and reconnect/resync host tests.
- [ ] Wire the client through the application service boundary and production
  authenticated loopback gateway; use an ephemeral port and per-launch secret.
- [ ] Verify Basic auth on health, SSE, and every route; test rejection from a
  second app UID before treating loopback as protected.
- [ ] Validate bounded response budgets against large-session and
  device-memory measurements.
- [ ] Build native Compose screens for chat, sessions, files, Git, terminal,
  settings, and diagnostics after architecture/runtime gates permit integration.
- [ ] Verify permission prompts are visible and answerable; show agent file
  and shell activity and do not silently approve actions.
- [ ] Measure app/UI SSE recovery and event-to-paint latency; run STR-5 live
  device testing with 500 drops and authoritative-state reconciliation.
- [ ] Label context-window usage as estimated and derive it from assistant
  token counts/model context limits as specified.

## 5. Workspace, Git, and terminal

- [x] Implement host workspace path guards, ranged reads, optimistic write
  preconditions, atomic mutations, trash/restore, and bounded search with
  host tests.
- [x] Preserve host evidence for the 100,000-case path run, 1,000-interruption
  atomic-write stress, and sparse 1 GiB bounded-range test.
- [x] Measure WS-3 conflict and WS-4 memory/latency budgets on the reference
  device (50/50 conflicts rejected, 3 ms/4 ms first screen, +0 MB PSS).
- [ ] Pass WS-2 on device: the 2026-10-30 1,000-run attempt observed 0 commits
  and 2 bind failures; harness retry/cleanup/control fix applied, rerun pending.
- [ ] Validate durability and the WS-5 file-operation matrix on required target
  filesystems, and exercise image decode within the 32 MB bound.
- [ ] Wire WorkspaceService through the production composition root; test
  hostile paths, symlinks, conflicts, interruption, and restore flows.
- [ ] Implement guest Git as the single source of truth and measure status on
  the specified 5,000-file repository before considering JGit.
- [ ] Implement native `forkpty`/PtyService with bounded scrollback and session
  cleanup; test interaction, resize, signals, and process-tree termination.
- [ ] Compare agent-shell and native-PTY `pwd`, `node -v`, and `PATH` on device;
  keep server `/pty` out of the design because the pinned route is unsuitable.

## 6. Security and privacy

- [~] Core credential generation/authentication and an encrypted-file
  `SecretStore` adapter have host coverage; production gateway/lifecycle
  binding and Android Keystore device verification remain open.
- [~] SEC-1: the second-UID matrix passes 30/30 (invalid auth rejected, Basic
  accepted); repeat through the production Android Keystore-backed gateway.
- [ ] SEC-2: scan APK, logcat, app logs, crash dumps, and process arguments for
  secrets; implement production log redaction and credential handling.
- [ ] SEC-3: run the merged release-manifest checker in Android CI and inspect
  the packaged release manifest for exported components, backups, permissions,
  and cleartext policy.
- [ ] SEC-5: finish SBOM/dependency-closure verification against built
  artifacts and run the required vulnerability scan.
- [ ] Keep provider credentials out of URLs, store them with restrictive
  permissions, and test provisioning, isolation, deletion, and redaction.
- [ ] Add repo-hook and hostile-repository fixtures; ensure Git hook execution
  and agent permission requests are visible and explicitly authorized.
- [ ] Enforce path containment and approved bind mounts; document that PRoot is
  not a security sandbox and is an accepted residual risk.
- [ ] Complete downgrade/update policy, signing verification, immutable
  artifact handling, and rollback tests.
- [ ] Keep remote-provider TLS 1.2+ / fail-closed SEC-6 out of v1 acceptance;
  implement and test it before the v1.1 remote profile.
- [ ] Review exported diagnostic reports before sharing; they can contain
  device/build metadata, local paths, and session identifiers.

## 7. Reference-device evidence and spikes

- [x] S1-A harness evidence accepted for G0 (2026-10-27). Production Profile A
  and Profile B remain post-G0 follow-ups.
- [ ] Complete S2 Profile A/B measurements on the Infinix X6870 for cold
  start, RSS, 10k-file Git status, npm test, device behavior, and thermals;
  apply the documented rules.
- [x] S3 harness evidence accepted for G0 (2026-10-27): guest-shell/native-PTY
  behavior verified on the Infinix X6870; production agent-shell comparison is
  a post-G0 follow-up.
- [x] S4 host SSE reconnect/resync and S5 host endpoint/auth contract evidence
  are recorded; app/UI and device acceptance remain separate.
- [ ] Complete S6 only when a distributable 2.x artifact can be evaluated;
  retain the current 1.19.3 pin in the meantime.
- [ ] Complete S9 legal, dependency, source-offer, signing, and reproducible
  build requirements before release.
- [ ] RT-5 boot on a 16 KB-page device is out of scope for v1; keep the CI
  ELF-alignment check and revisit only if more hardware is supported.
- [ ] For every report record Android version, ABI, page size, thermal
  status, run count, APK hash, runtime source/location, and logs.
- [ ] Promote evidence rows only by linking admissible reports in
  `docs/evidence/RESULTS.md`; preserve invalidated and historical reports as
  historical, not as passes.

## 8. Distribution and release readiness

- [x] Record distribution decision and generate initial SBOM, notices,
  license texts, and source-offer draft.
- [ ] Resolve Bun LGPL object-file/relink/source-offer obligations and verify
  dependency closure against the shipped binary.
- [ ] Complete source offers and license/NOTICE inventory for every shipped
  runtime and native dependency.
- [ ] Produce reproducible release builds, verify signing identity and
  upgrade/rollback behavior, and publish an installable signed artifact.
- [ ] Validate release APK manifest/security checks and the supported
  Infinix X6870 configuration before publication.
- [ ] Document sideload/advanced-install and developer-verification guidance;
  monitor the policy rollout identified in the distribution ADR.
- [ ] Publish release notes, runtime pins, SBOMs, notices, and source offers
  with each release; retain evidence and checksums.

## 9. Final acceptance review

- [ ] Every required G0 decision is frozen from admissible evidence and the
  phase plan no longer lists a blocking G0 dependency.
- [ ] Every requirement in `docs/traceability.md` has an implementation
  reference, evidence link, status, and remaining acceptance item.
- [ ] Threat-model residual risks are explicitly accepted or mitigated, with
  no unverified control described as implemented.
- [ ] Required host tests, Android builds, CI checks, reference-device runs, and
  release compliance checks pass on the exact candidate APK/runtime hashes.
- [ ] No device row is promoted solely by host tests, harness scaffolding,
  export timing, or owner reports without supporting device evidence;
  explicit owner overrides must cite that evidence.