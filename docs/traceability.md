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
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.100), host/device harness evidence, `core/runtime` verified-artifact boundaries, and test-harness staging for native PRoot entrypoints exist; the isolated harness downloads pinned runtime archives concurrently and supports Alpine APK PAX metadata | Test-only auto-fetch implementation; runtime-download retry and loader/guest-shell/native-PTY probes pass in report `docs/evidence/spike-20261024-153116.json`. Production PRoot/runtime-package delivery, app-native entrypoint packaging and process supervision remain incomplete | Run required Tier A/B/C, 16 KB-page and lifecycle tests; resolve PRoot package delivery and the D4 profile; keep production runtime wiring gated on G0 |
| D4: pinned official OpenCode musl binary; optional user toolchains | `runtime/manifest.json` pins official archive, executable, Alpine rootfs, and runtime-library package digests; `core/runtime` has HTTPS-only bounded streaming downloads, SHA-256 verification and atomic artifact publication wired into the pinned OpenCode/Alpine installers | Host-tested pinned OpenCode fetch-to-install path added; device harness download/install hashes are recorded in `docs/evidence/spike-20261024-153116.json`. PRoot and APK package delivery, cold-network timing, production launch/supervision and app wiring remain incomplete | Validate cold first install, cache reuse, interrupted-download retry, PRoot/runtime libraries and hash rejection in the staged APK; integrate production lifecycle only after G0 |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, reconnecting SSE, endpoint-specific bounded JSON response reads, and an `E_TOO_LARGE` result; SSE remains streaming and is not buffered by the JSON guard | Kotlin client extracted; bounded response protection added; app/service wiring pending | Run focused client tests, validate provisional budgets against large-session/device-memory evidence, wire through the application service boundary, and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | `core/security/` generates a random 256-bit per-launch password, stores it through `SecretStore`, and verifies HTTP Basic credentials without ordinary string comparison; `app/` now has an Android Keystore AES-GCM encrypted-file `SecretStore` adapter with opaque reference filenames | Host security contract tests and app compilation pass; encrypted-file behavior has host unit tests. The adapter is not yet bound to a server listener or application lifecycle; actual Android Keystore, second-UID SEC-1 and SEC-2 device tests remain pending | Add the G0-approved server lifecycle/gateway binding; run Android Keystore instrumentation, SEC-1 from a second UID and the SEC-2 secret scan |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract; `core/workspace/` implements guarded host paths, ranged reads, optimistic writes requiring size, mtime, and SHA-256 matches, atomic mutations, trash/restore, and bounded result search; host tests cover the 60-case WS-5 file/folder matrix and same-size/same-mtime external content conflicts | Host implementation and matrix/conflict tests pass. The opt-in WS-2 SIGKILL stress passed 1,000 host interruptions across 1 KiB–50 MiB writes; a sparse 1 GiB file range-read test verifies bounded transfer. App wiring, measured memory/latency budgets and device acceptance remain pending | Measure WS-4 on required devices, repeat WS-3/WS-5 on target filesystems, and wire only after the applicable G0 gate |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| SEC-3: release manifest security | `scripts/check-release-manifest.py` validates the merged release manifest for exported components, backup policy, permissions, and app-wide cleartext; CI runs it after release assembly | Manifest merge check implemented; APK build/CI acceptance pending | Run the Android CI build and lint the packaged release manifest; configure loopback-only cleartext only when the G0-approved gateway is integrated |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` integrates verified OpenCode and Alpine base-rootfs artifact installation; the host workspace service is now implemented behind `core/workspace-api` | Keep PRoot package/launcher work gated by the unresolved runtime profile decision; complete workspace durability/device gates and the remaining terminal/security implementations in order. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Harness and host staging complete; latest physical report passes pinned runtime download/install, loader, guest-shell/native-PTY, and FGS start/stop probes on one 4 KB-page Infinix device; isolated authenticated S1 server/mock-prompt action is implemented but not yet device-tested; S8 records text changes but no key-down events | Run S1 server/prompt and S3/S7/S8 on Tier A/B/C and 16 KB-page/keyboard devices; capture APK hash and complete keyboard/lifecycle coverage |
| Physical-device baseline before freezing provisional budgets | `docs/runtime-matrix.md`, `docs/evidence/RESULTS.md`, and `docs/plan/phase-0.md` | Blocked | Run the listed device experiments and record measured evidence before freezing affected decisions and thresholds |

## Evidence boundaries

- `npm test`, `npm run typecheck`, the architecture check, and bridge
  parity check validate the tracked contracts and host-side bridge fixture.
- Focused runtime/model/client/workspace Kotlin tests and `coreCheck` passed
  on JDK 21. Workspace host coverage includes the 100,000-case traversal and
  normalization rejection run, the 1,000-interruption atomic-write stress,
  and a sparse 1 GiB bounded-range test. `:app:assembleDebug` and app unit
  tests also passed on JDK 21.
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
