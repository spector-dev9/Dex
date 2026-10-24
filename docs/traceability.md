# Plan traceability

This map tracks the root `plan.md` decisions against the current repository
and distinguishes executable contract evidence from implementation or
device acceptance. A passing contract test does not imply that the
corresponding Android service exists. G0 decisions use the separate IDs in
`docs/spec.md`; see `docs/plan/phase-0.md` for their status.

| Plan item | Current evidence | Status | Remaining acceptance |
| --- | --- | --- | --- |
| D1: refactor the existing Kotlin/Compose architecture | `app/` is a minimal Compose shell; `core/model` and `core/opencode-client` now contain pure Kotlin models and an authenticated HTTP/SSE client | In progress | Continue the staged migration; Android app wiring and device acceptance remain pending |
| D2: native Jetpack Compose UI | `app/src/main/kotlin/` contains the Compose entry point and license screen | Scaffold | Implement feature screens after the architecture and runtime gates are resolved |
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.100) and host spike records exist; host guest shell revalidated with the .100 pin and staged loader | Host evidence only | Verify packaging, startup, and supported ABI behavior on physical devices |
| D4: pinned OpenCode musl binary; optional user toolchains | `runtime/manifest.json`; `contract/tests/contract.test.mjs` checks the version pin and required artifacts | Contract-checked | Verify install, integrity enforcement, and runtime behavior on target devices |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, and reconnecting SSE | Kotlin client extracted; app/service wiring pending | Wire through the application service boundary and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | Authentication behavior is recorded in `tests/spikes/results/` and asserted by contract tests | Not implemented | Add listener lifecycle and secret provisioning; verify every route rejects unauthenticated clients |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract | Contract only | Implement guarded paths, atomic writes, conflict detection, and operations; run WS gates |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model and client extraction established in `core/model` and `core/opencode-client`. Earlier focused test reports passed (3 model, 4 client); recent forced Gradle attempts have lost the daemon, once at `:core:model:compileKotlin` and again during project configuration | Re-run fresh Kotlin tests and keep the Android debug build green before runtime extraction. `assembleDebug` most recently stopped at `:app:compileDebugKotlin` with the same daemon failure. Upstream runtime sources have been identified and tracked in `docs/UPSTREAM.md`; they require adaptation to this repository's contracts and pinned API |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Scaffold and host staging complete; no device run | Run S1/S3/S7/S8 on Tier A/B/C, 16 KB-page and keyboard devices; add the native PTY comparison |
| Physical-device baseline before freezing provisional budgets | `docs/runtime-matrix.md`, `docs/evidence/RESULTS.md`, and `docs/plan/phase-0.md` | Blocked | Run the listed device experiments and record measured evidence before freezing affected decisions and thresholds |

## Evidence boundaries

- `npm test`, `npm run typecheck`, the architecture check, and bridge
  parity check validate the tracked contracts and host-side bridge fixture.
- `./gradlew -PskipApp=true coreCheck` validates the Kotlin API modules and
  compiled bridge parity. It does not build the Android app.
- The focused Gradle invocation without forced task execution was
  `UP-TO-DATE`; it did not rerun tests. A forced model/client test run lost
  the daemon at `:core:model:compileKotlin`. A subsequent isolated forced
  compile lost the daemon during project configuration. `assembleDebug` lost
  it at `:app:compileDebugKotlin`; none emitted a Kotlin diagnostic. The
  cgroup reported no OOM kills during the latest attempt, so the cause remains
  undetermined. Treat fresh Kotlin validation and the debug-build gate as
  unverified until those commands complete successfully. No physical-device
  smoke test was run.
- Host spikes are not physical-device acceptance. The authoritative device
  statuses remain in `docs/evidence/RESULTS.md`.
