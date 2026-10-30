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
| D3: Alpine/PRoot runtime, arm64 primary | Runtime manifest (PRoot 5.1.107.101), host/device harness evidence, `core/runtime` verified-artifact installers for Alpine rootfs/runtime libraries, and test-harness staging for native PRoot entrypoints exist; the isolated harness downloads pinned runtime archives concurrently and supports Alpine APK PAX metadata | Host tests cover manifest-driven OpenCode, Alpine rootfs, pinned runtime-library and Node package installation. Historical two-device runs (Infinix X6870 and Nothing A001T, Android 16/arm64) reported pinned runtime installation, loader launch, Node guest shell and native-PTY environment match; v1 G0 uses the Infinix X6870 only. Production PRoot integration and process supervision remain incomplete and gated on G0 | Run S1/S2/S3 on the Infinix X6870, resolve native PRoot packaging and the D4 profile; do not enable production process launch before G0 |
| D4: pinned official OpenCode musl binary; optional user toolchains | `runtime/manifest.json` pins official archive, executable, Alpine rootfs, and runtime-library package digests; `core/runtime` now orchestrates HTTPS-only bounded downloads, SHA-256 verification, atomic artifact publication and pinned Alpine library installation | Host-tested `installPinnedRuntime()` path provisions the pinned OpenCode artifact, Alpine rootfs and listed runtime libraries. Device harness download/install hashes are recorded in `docs/evidence/spike-20261024-153116.json`. Native PRoot delivery, production launch/supervision and app lifecycle wiring remain incomplete | Validate cold first install, cache reuse, interrupted-download retry, PRoot/native entrypoint integration and hash rejection in the staged APK; integrate production lifecycle only after G0 |
| D5: authenticated HTTP plus SSE | Pinned OpenAPI 1.19.3 snapshot, Node fixtures, and `core/opencode-client` with `/session/*/prompt_async`, `/event`, Basic auth, typed responses, reconnecting SSE, endpoint-specific bounded JSON response reads, and an `E_TOO_LARGE` result; SSE remains streaming and is not buffered by the JSON guard | Kotlin client extracted; bounded response protection added; app/service wiring pending | Run focused client tests, validate provisional budgets against large-session/device-memory evidence, wire through the application service boundary, and verify authenticated SSE recovery in the app and on target devices |
| D6: ephemeral loopback listener and per-launch password | `core/security/` generates a random 256-bit per-launch password, stores it through `SecretStore`, and verifies HTTP Basic credentials without ordinary string comparison; `app/` now has an Android Keystore AES-GCM encrypted-file `SecretStore` adapter with opaque reference filenames | Host security contract tests and app compilation pass; encrypted-file behavior has host unit tests; the second-UID SEC-1 matrix passes 30/30 against the harness gateway. The adapter is not yet bound to a server listener or application lifecycle; actual Android Keystore and SEC-2 device tests remain pending | Add the G0-approved server lifecycle/gateway binding; repeat SEC-1 through the production path, run Android Keystore instrumentation and the SEC-2 secret scan |
| D7: native host-filesystem workspace operations | `core/workspace-api/` defines the service contract; `core/workspace/` implements guarded host paths, ranged reads, optimistic writes requiring size, mtime, and SHA-256 matches, atomic mutations, trash/restore, and bounded result search; host tests cover the 60-case WS-5 file/folder matrix and same-size/same-mtime external content conflicts | Host implementation and matrix/conflict tests pass. The opt-in WS-2 SIGKILL stress passed 1,000 host interruptions across 1 KiB–50 MiB writes; a sparse 1 GiB file range-read test verifies bounded transfer; the reference-device harness passed WS-3 (50/50 conflicts rejected, no silent overwrite) and WS-4 (3 ms/4 ms first screen, +0 MB PSS, 40/40 reads). The first full 1,000-run device WS-2 attempt failed with 0 commits and 2 bind failures (no corruption); harness retry/cleanup/control fix applied, rerun pending. Production app wiring remains pending | Repeat WS-5 on required target filesystems, exercise image decode, rerun WS-2, and wire only after the applicable G0 gate |
| D8: native interactive PTY | `core/terminal-api/` defines the service contract; spike records show the server PTY is unsuitable | Contract only | Implement the native PTY helper and run interactive, signal, resize, and cleanup tests |
| D9: Alpine Git as the single source of truth | `core/git-api/` defines Git operations | Contract only | Implement guest Git and measure the specified 5,000-file status workload before considering JGit |
| D10: GitHub Releases and F-Droid distribution | `docs/adr/0001-distribution.md` records the channel decision; license checklist and SBOMs are present | Decision recorded; release blockers remain | Complete source offers, reproducible builds, signing, trademark, and release compliance |
| SEC-3: release manifest security | `scripts/check-release-manifest.py` validates the merged release manifest for exported components, backup policy, permissions, and app-wide cleartext; CI runs it after release assembly | Manifest merge check implemented; APK build/CI acceptance pending | Run the Android CI build and lint the packaged release manifest; configure loopback-only cleartext only when the G0-approved gateway is integrated |
| Staged migration: contract tests and behavior traceability first | `contract/tests/contract.test.mjs`, `ui/src/bridge/*.test.ts`, this map | Prerequisite present; model/client extraction is established; `core/runtime` integrates verified OpenCode and Alpine base-rootfs artifact installation; the host workspace service is now implemented behind `core/workspace-api` | Keep PRoot package/launcher work gated by the unresolved runtime profile decision; complete workspace durability/device gates and the remaining terminal/security implementations in order. Upstream runtime sources in `docs/UPSTREAM.md` still require adaptation |
| Phase 2 device harness | `tests/spikes/harness/`, `scripts/stage-spike-harness.py` | Two earlier reports identify the same APK SHA-256 (`a5e0deb3ab7e6bf61983803a3a8ea3908d3aa064186d449f286d26e7105980b6`) on Infinix X6870 and Nothing A001T, both Android 16/arm64/4 KB pages. The 2026-10-26 report (`evidence/spike-20261026-064348.json`, APK SHA-256 `6637e095c518af0f678626c23dd194a2a2d5ff133d90093eb4b391382eb5bfab`) passes the S1/S3 harness suite 11/11 and three scripted PTY byte round-trips on an Infinix X6870. This validates feasibility, not physical keyboard mapping, production integration or full G0 acceptance on the Infinix X6870; 16 KB-page devices are out of scope for v1. | Record the remaining S1/S2/S3 reference-device evidence; production terminal input is tracked separately |
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
  against the pinned Alpine 3.20.3 archive on the host. Runtime package
  installation now verifies package pins and atomically installs only the
  required library files; focused runtime tests cover this path. PRoot native
  packaging, full packaged-asset validation, and the production platform
  launcher/supervisor remain incomplete. Two device report summaries record
  matching APK hashes and successful isolated harness loader, guest-shell,
  native-PTY, authenticated-health, and mock-prompt probes. These are
  isolated harness results, not production-app physical-device smoke tests
  or full G0 acceptance.
- Host spikes are not physical-device acceptance. The authoritative device
  statuses remain in `docs/plan/phase-0.md`.
