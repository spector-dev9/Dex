# Project Checklist

This checklist consolidates the project plan, threat model, traceability map,
and current Phase 0/device-gate status. It is a working index, not a replacement
for those source documents or their detailed acceptance criteria.

Status: `[x]` complete/green, `[~]` partial/in progress, `[ ]` open. Each
reported result identifies whether it is host, harness, or device evidence. A
passing isolated probe does not automatically promote a broader device or G0
acceptance gate.

Run the repository's automatable G0 checks with `python3 scripts/run-g0-suite.py`;
use `--host-only` for host checks or `--list` to inspect the suite. The runner
reports physical-device, unavailable-artifact, and owner-decision gates
separately. Passing automated checks alone does not unblock G0.

## Current Test Status

- [x] Gated spike APK assembles successfully; latest reported APK SHA-256 is
  `b07762bd4b598b7f07ecccbbce28e5f43d94a5649ab12d7da48c04ccfc9bca5b`.
- [x] S1 authenticated-health and mock-provider smoke: two user-reported runs
  passed; see `tests/spikes/results/S1-server-mock-20261024.md`.
- [x] S8 Gboard field-input test: owner-reported green with text, IME,
  hardware-key, selection, and context-menu events; see
  `tests/spikes/results/S8-ime-observations-20261025.md`.
- [x] Document the Gboard report's `assessed`/APK `not-assessed` label
  discrepancy; retain it as a provenance note, not a failing test.
- [x] Record the fixed-harness S1/S3 device suite: 10/10 available probes
  passed on one Infinix X6870; this remains pre-G0 feasibility evidence, not
  Tier A/B/C acceptance. See
  `tests/spikes/results/S1-S3-device-suite-20261025.md`.
- [ ] Run native-terminal TERM-5 with Gboard and the formal S7 active-task
  acceptance matrix; these are separate from the completed checks above.

## 1. Decisions and project gates

- [x] Keep the native Kotlin/Compose direction and staged migration recorded.
- [x] Record GitHub Releases + Obtainium first, self-hosted F-Droid after
  reproducible builds, and defer Play; see `docs/adr/0001-distribution.md`.
- [x] Keep OpenCode 1.19.3 as the current exact baseline; verify its manifest
  hash and refuse unknown major versions.
- [ ] Complete G0: run S1-S9 as applicable, gather Tier A/B/C baselines, ratify
  provisional thresholds, and freeze the decisions governed by `docs/spec.md`.
- [ ] Resolve D4 runtime profile using the documented S1/S3/S2 decision rules;
  do not encode a default before the evidence is admissible.
- [ ] Resolve the S6 2.x artifact check when a distributable artifact exists;
  do not change the current 1.19.3 pin based on an unavailable artifact.
- [ ] Keep production runtime/service wiring gated until G0 is complete; use
  only isolated disposable/debug harnesses for pre-G0 feasibility evidence.
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
- [ ] Complete remaining G1 device acceptance: RT-1..RT-5, SEC-1/2, STR-1/5,
  LC-3, and WS-1/2 as specified in the phase plan.
- [ ] Add production service wiring only after applicable G0 gates; keep test
  fakes and the partial bridge handler clearly test-only.
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

## 4. S7 lifecycle and device acceptance

- [~] The isolated harness has a 10-minute quick foreground-service diagnostic,
  partial wake lock, separate alarm heartbeat, and power-state telemetry;
  validate it on devices as a diagnostic only.
- [~] The 2026-10-26 Infinix diagnostic records 631,745 ms service survival
  and zero restarts; the child remained alive at manual stop. The owner
  classifies sparse samples (31/62, with two gaps over 25 seconds) as Android
  scheduling behavior, not child-process failure. It was not a continuous
  screen-off agent task. See `tests/spikes/results/S7-soak-20261026-041939.md`.
- [ ] Confirm S7 represents an active agent task with screen off, not an idle
  foreground service. Idle freeze/suspend is not a failure by itself.
- [ ] For each run capture monotonic `elapsedRealtime`, alarm heartbeat,
  service heartbeat/restarts, `isInteractive`, `isDeviceIdleMode`,
  `isPowerSaveMode`, and battery-optimization exemption state.
- [ ] Capture `dumpsys activity services`, `dumpsys deviceidle`, and logcat
  evidence for `am_kill`, `lmkd`, and fatal process errors.
- [ ] Treat alarm delivery as diagnostic only; do not assume Doze/OEM policy
  guarantees a fixed alarm cadence.
- [x] Quick diagnostic auto-stops after 10 minutes and rejects early exports;
  this is separate from the formal 30-minute acceptance run.
- [ ] For formal acceptance, use monotonic elapsed time for the full 30-minute
  active-task soak; never count start-to-export wall time as survival duration.
- [ ] Run Tier A/B/C with screen off, 19 of 20 successful repeats per tier,
  and record OEM settings, device/build identity, APK hash, ABI, page size,
  thermal state, and all report artifacts.
- [ ] Update S7/LC-2 only after active-task runs pass the specified criteria;
  prior exports and the latest 10-minute service diagnostic do not satisfy
  the 30-minute screen-off agent-task requirement.
- [ ] Run separate LC-5 four-hour soak and LC-6 thermal/resource checks on
  their required tiers; do not infer them from S7.

## 5. S8 keyboard observation and acceptance

- [x] The 2026-10-26 device report passes three scripted PTY byte round-trips
  on one Infinix X6870, including Enter/ICRNL translation; this checks PTY
  transport, not physical keyboard input. See
  `docs/evidence/spike-20261026-064348.json`.
- [x] Gboard field-input observation is green; the report records text,
  composing, IME-key, hardware-key, selection, and context-menu events.
- [x] Record the owner-supplied Hacker's Keyboard field observation: two
  records passed with hardware/IME key events, but `keyboardAcceptance` is
  `not-assessed` and no Ctrl/Fn-to-PTY mapping is captured. See
  `tests/spikes/results/S8-hackers-keyboard-20261026.md`.
- [x] `keyboardAcceptance=not-assessed` is intentional for this EditText-only
  probe and is not a failure. This run does not exercise the native PTY,
  ESC/Tab/Ctrl/Alt/arrow mapping, or the TERM-5 acceptance requirements.
- [ ] Run the PTY-backed TERM-5 probe with Gboard and Samsung Keyboard for
  text entry, Enter, Backspace, paste, and selection. Verify Esc, Tab, Ctrl-C,
  Alt, and arrow byte sequences from the app-provided terminal extra-key row;
  do not expect soft keyboards to provide Ctrl or Fn keys.
- [ ] Keep `keyboardAcceptance=not-assessed` until the PTY-backed terminal
  probe is run and the exact byte mapping matches on both keyboards.
- [x] Treat zero hardware `KeyEvent` key-downs from a soft IME as expected,
  not as a keyboard failure.
- [ ] Complete TERM-5 on the native PTY with Gboard and Samsung Keyboard,
  including IME input and paste/select, app-provided extra keys, resize,
  signals, and cleanup. Fn is not a required key mapping.

## 6. OpenCode client, UI, and session behavior

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

## 7. Workspace, Git, and terminal

- [x] Implement host workspace path guards, ranged reads, optimistic write
  preconditions, atomic mutations, trash/restore, and bounded search with
  host tests.
- [x] Preserve host evidence for the 100,000-case path run, 1,000-interruption
  atomic-write stress, and sparse 1 GiB bounded-range test.
- [ ] Measure workspace memory/latency budgets on target devices and validate
  durability and file-operation matrices on required target filesystems.
- [ ] Wire WorkspaceService to the app only after applicable G0 approval;
  test hostile paths, symlinks, conflicts, interruption, and restore flows.
- [ ] Implement guest Git as the single source of truth and measure status on
  the specified 5,000-file repository before considering JGit.
- [ ] Implement native `forkpty`/PtyService with bounded scrollback and session
  cleanup; test interaction, resize, signals, and process-tree termination.
- [ ] Compare agent-shell and native-PTY `pwd`, `node -v`, and `PATH` on device;
  keep server `/pty` out of the design because the pinned route is unsuitable.

## 8. Security and privacy

- [~] Core credential generation/authentication and an encrypted-file
  `SecretStore` adapter have host coverage; production gateway/lifecycle
  binding and Android Keystore device verification remain open.
- [ ] SEC-1: instrument Android Keystore and verify unauthorized second-UID
  requests are rejected across protected routes.
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

## 9. Physical-device matrix and spike evidence

- [ ] Complete S1 on Tier A/B/C with the official pinned artifact and accepted
  app/runtime path; existing two-device results are feasibility evidence only.
- [ ] Complete S2 Profile A/B measurements for cold start, RSS, 10k-file Git
  status, npm test, device behavior, and thermals; apply the documented rules.
- [~] S3 guest-shell/native-PTY behavior is verified by two isolated harness
  summaries on Android 16/arm64/4 KB-page devices; production agent-shell,
  representative tiers, and 16 KB-page coverage are open.
- [x] S4 host SSE reconnect/resync and S5 host endpoint/auth contract evidence
  are recorded; app/UI and device acceptance remain separate.
- [ ] Complete S6 only when a distributable 2.x artifact can be evaluated;
  retain the current 1.19.3 pin in the meantime.
- [ ] Complete S9 legal, dependency, source-offer, signing, and reproducible
  build requirements before release.
- [ ] Run the required 16 KB-page device tests, including RT-5 boot and
  `execute_no_trans` scan; “16KB-ok” ELF alignment on a 4 KB-page device is
  not 16 KB-device evidence.
- [ ] For every device report record Android version, tier, ABI, page size,
  thermal status, run count, APK hash, runtime source/location, and logs.
- [ ] Promote evidence rows only by linking admissible reports in
  `docs/evidence/RESULTS.md`; preserve invalidated and historical reports as
  historical, not as passes.

## 10. Distribution and release readiness

- [x] Record distribution decision and generate initial SBOM, notices,
  license texts, and source-offer draft.
- [ ] Resolve Bun LGPL object-file/relink/source-offer obligations and verify
  dependency closure against the shipped binary.
- [ ] Complete source offers and license/NOTICE inventory for every shipped
  runtime and native dependency.
- [ ] Produce reproducible release builds, verify signing identity and
  upgrade/rollback behavior, and publish an installable signed artifact.
- [ ] Validate release APK manifest/security checks and required supported
  ABI/page-size device matrix before publication.
- [ ] Document sideload/advanced-install and developer-verification guidance;
  monitor the policy rollout identified in the distribution ADR.
- [ ] Publish release notes, runtime pins, SBOMs, notices, and source offers
  with each release; retain evidence and checksums.

## 11. Final acceptance review

- [ ] Every required G0 decision is frozen from admissible evidence and the
  phase plan no longer lists a blocking G0 dependency.
- [ ] Every requirement in `docs/traceability.md` has an implementation
  reference, evidence link, status, and remaining acceptance item.
- [ ] Threat-model residual risks are explicitly accepted or mitigated, with
  no unverified control described as implemented.
- [ ] Required host tests, Android builds, CI checks, device-tier runs, and
  release compliance checks pass on the exact candidate APK/runtime hashes.
- [ ] No S7/S8 or other device row is promoted solely by host tests, harness
  scaffolding, export timing, or owner-reported results without supporting
  device evidence.