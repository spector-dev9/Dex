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
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.100), host/device harness evidence, and `core/runtime` validation plus an atomic verified-artifact installation boundary exist; strict OpenCode extraction is implemented, while Alpine extraction and process launch remain platform boundaries | Host implementation and partial device feasibility only | Implement and wire safe Alpine/rootfs extraction and a native-library launcher; verify complete packaging, startup, and ABI behavior on physical devices |
| D4: pinned official OpenCode musl binary; optional user toolchains | `runtime/manifest.json` pins the official 1.19.3 ARM64 musl archive and extracted binary hashes plus Alpine runtime libraries; readiness and staging verify the binary digest; the installer has a strict single-executable tar.gz extractor | Archive and executable integrity checks plus an isolated install transaction exist; production wiring and full runtime-content verification remain incomplete | Integrate the installer, validate all installed runtime contents against trusted metadata, and verify installation/update behavior on target devices |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, and reconnecting SSE | Kotlin client extracted; app/service wiring pending | Wire through the application service boundary and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | Authentication behavior is recorded in `tests/spikes/results/` and asserted by contract tests | Not implemented | Add listener lifecycle and secret provisioning; verify every route rejects unauthenticated clients |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract | Contract only | Implement guarded paths, atomic writes, conflict detection, and operations; run WS gates |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` is separate with manifest/file checks, archive and OpenCode executable hash verification, tested atomic replacement, and strict OpenCode tar.gz extraction | Integrate install into runtime state management, add safe Alpine/rootfs extraction, cover update rollback and all installed artifacts, and provide a secure platform launcher; then rerun the isolated device smoke path. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Harness and host staging complete; official-package physical report passes loader, guest-shell/native-PTY, and FGS start/stop probes on one 4 KB-page Infinix device; S8 records text changes but no key-down events | Run S1/S3/S7/S8 on Tier A/B/C, 16 KB-page and keyboard devices; capture artifact hash and complete keyboard/lifecycle coverage |
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
  production platform launcher remain incomplete. The isolated harness
  passed loader, guest-shell and native-PTY checks on one device for the
  current official OpenCode version; its report omits the staged archive
  hash. No production-app physical-device smoke test was run.
- Host spikes are not physical-device acceptance. The authoritative device
  statuses remain in `docs/evidence/RESULTS.md`.
