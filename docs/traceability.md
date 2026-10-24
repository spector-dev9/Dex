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
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.100), host/device harness evidence, and `core/runtime` verified-artifact boundaries exist; strict OpenCode and Alpine extraction plus immutable pinned rootfs installation are implemented, while PRoot installation and process launch remain incomplete | Host implementation and partial device feasibility only | Integrate pinned runtime packages and a native-library launcher; verify complete packaging, startup, and ABI behavior on physical devices |
| D4: pinned official OpenCode musl binary; optional user toolchains | `runtime/manifest.json` pins official archive and executable digests plus Alpine runtime-library pins; `ManifestRuntimeManager` verifies the trusted OpenCode and Alpine pins, publishes immutable version directories, atomically activates the OpenCode version, and revalidates OpenCode readiness | OpenCode and Alpine base-rootfs install paths exist in core; runtime dependency installation, full runtime-content validation, and production wiring remain incomplete | Integrate the complete runtime bundle and update/rollback lifecycle, validate every installed asset, and verify installation on target devices |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, and reconnecting SSE | Kotlin client extracted; app/service wiring pending | Wire through the application service boundary and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | Authentication behavior is recorded in `tests/spikes/results/` and asserted by contract tests | Not implemented | Add listener lifecycle and secret provisioning; verify every route rejects unauthenticated clients |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract | Contract only | Implement guarded paths, atomic writes, conflict detection, and operations; run WS gates |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` integrates verified OpenCode and Alpine base-rootfs artifact installation | Integrate pinned runtime package installation and full asset validation; complete update/rollback and secure platform launching, then rerun the isolated device smoke path. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Harness and host staging complete; official-package physical report passes loader, guest-shell/native-PTY, and FGS start/stop probes on one 4 KB-page Infinix device; S8 records text changes but no key-down events | Run S1/S3/S7/S8 on Tier A/B/C, 16 KB-page and keyboard devices; capture artifact hash and complete keyboard/lifecycle coverage |
| Physical-device baseline before freezing provisional budgets | `docs/runtime-matrix.md`, `docs/evidence/RESULTS.md`, and `docs/plan/phase-0.md` | Blocked | Run the listed device experiments and record measured evidence before freezing affected decisions and thresholds |

## Evidence boundaries

- `npm test`, `npm run typecheck`, the architecture check, and bridge
  parity check validate the tracked contracts and host-side bridge fixture.
- Focused runtime/model/client Kotlin tests and `coreCheck` passed on JDK 21.
  `:app:assembleDebug` also passed on JDK 21. These are host build results,
  not emulator or physical-device runs.
- The OpenCode installer verifies the pinned archive and extracted executable,
  publishes immutable versioned directories, and switches the active version
  with an atomic marker. The Alpine installer verifies the manifest pin and
  publishes an immutable content-addressed rootfs directory. Its extractor
  validates paths, symlinks, entry types and permissions and was exercised
  against the pinned Alpine 3.20.3 archive on the host. PRoot and runtime
  package installation, full asset validation, and the production platform
  launcher remain incomplete. The isolated harness passed loader, guest-shell
  and native-PTY checks on one device for the current official OpenCode
  version; its report omits the staged archive hash. No production-app
  physical-device smoke test was run.
- Host spikes are not physical-device acceptance. The authoritative device
  statuses remain in `docs/evidence/RESULTS.md`.
