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
| D6: ephemeral loopback listener and per-launch password | `core/security/` generates a random 256-bit per-launch password, stores it through `SecretStore`, and verifies HTTP Basic credentials without ordinary string comparison | Credential component implemented; Keystore-backed storage, listener lifecycle, and app wiring pending | Integrate with the G0-approved listener and Keystore store; run SEC-1 from a second UID and complete the SEC-2 secret scan |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract; `core/workspace/` implements guarded host paths, ranged reads, optimistic writes, atomic mutations, trash/restore, and bounded result search; host tests cover the 60-case WS-5 file/folder operation matrix | Host implementation and matrix tests; app wiring and device acceptance pending | Run the WS-2 interruption/durability stress and WS-5 matrix on target filesystems; measure WS-4 at 100 MB/1 GB and wire only after the applicable G0 gate |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` integrates verified OpenCode and Alpine base-rootfs artifact installation; the host workspace service is now implemented behind `core/workspace-api` | Keep PRoot package/launcher work gated by the unresolved runtime profile decision; complete workspace durability/device gates and the remaining terminal/security implementations in order. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Harness and host staging complete; official-package physical report passes loader, guest-shell/native-PTY, and FGS start/stop probes on one 4 KB-page Infinix device; S8 records text changes but no key-down events | Run S1/S3/S7/S8 on Tier A/B/C, 16 KB-page and keyboard devices; capture artifact hash and complete keyboard/lifecycle coverage |
| Physical-device baseline before freezing provisional budgets | `docs/runtime-matrix.md`, `docs/evidence/RESULTS.md`, and `docs/plan/phase-0.md` | Blocked | Run the listed device experiments and record measured evidence before freezing affected decisions and thresholds |

## Evidence boundaries

- `npm test`, `npm run typecheck`, the architecture check, and bridge
  parity check validate the tracked contracts and host-side bridge fixture.
- Focused runtime/model/client/workspace Kotlin tests and `coreCheck` passed
  on JDK 21. Workspace host coverage includes the 100,000-case traversal and
  normalization rejection run. `:app:assembleDebug` also passed on JDK 21.
  These are host build results, not emulator or physical-device runs.
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
