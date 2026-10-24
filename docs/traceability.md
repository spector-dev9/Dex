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
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.100), host spike records, and `core/runtime` manifest/file validation exist; process launch is an injected platform boundary | Host evidence and partial implementation only | Implement verified installation and platform launch; verify packaging, startup, and supported ABI behavior on physical devices |
| D4: pinned official OpenCode musl binary; optional user toolchains | `runtime/manifest.json` pins the official 1.19.3 ARM64 musl release and Alpine runtime libraries; `contract/tests/contract.test.mjs` checks the release URL/hash; `core/runtime` validates manifest/file presence and provides a streaming archive SHA-256 verifier | Official artifact and dependencies are pinned; production installer integration and installed-content integrity are incomplete | Wire archive verification into installation, validate extracted contents against trusted metadata, and rerun runtime behavior checks against the official binary on target devices |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, and reconnecting SSE | Kotlin client extracted; app/service wiring pending | Wire through the application service boundary and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | Authentication behavior is recorded in `tests/spikes/results/` and asserted by contract tests | Not implemented | Add listener lifecycle and secret provisioning; verify every route rejects unauthenticated clients |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract | Contract only | Implement guarded paths, atomic writes, conflict detection, and operations; run WS gates |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` is a separate module with manifest/file validation, archive-hash verification, and a platform process-launch port; the official release asset and Alpine C++ runtime libraries are now pinned | Implement atomic artifact installation and extracted-content verification plus a secure platform launcher; then rerun the isolated device smoke path against the official artifact. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Harness and host staging complete; previous physical run used the pre-official package and is not validation of the current official artifact | Run S1/S3/S7/S8 on Tier A/B/C, 16 KB-page and keyboard devices; rerun on the official package and add the native PTY comparison |
| Physical-device baseline before freezing provisional budgets | `docs/runtime-matrix.md`, `docs/evidence/RESULTS.md`, and `docs/plan/phase-0.md` | Blocked | Run the listed device experiments and record measured evidence before freezing affected decisions and thresholds |

## Evidence boundaries

- `npm test`, `npm run typecheck`, the architecture check, and bridge
  parity check validate the tracked contracts and host-side bridge fixture.
- Focused runtime/model/client Kotlin tests and `coreCheck` passed on JDK 21.
  `:app:assembleDebug` also passed on JDK 21. These are host build results,
  not emulator or physical-device runs.
- The runtime implementation currently validates manifest shape and required
  files only. The pinned manifest hashes the downloadable archive, not each
  extracted runtime file; runtime installation/content verification and the
  production platform launcher remain incomplete. No physical-device smoke
  test was run against the current official OpenCode artifact.
- Host spikes are not physical-device acceptance. The authoritative device
  statuses remain in `docs/evidence/RESULTS.md`.
