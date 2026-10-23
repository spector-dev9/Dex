# AndCode reuse review (research only)

Reference: `yuga-hashimoto/and-code`, commit
`70c970259c3e58e7a9f94267b9097c4280ad1c96` ("Bump Claude Code to 2.1.33
and Antigravity to 1.1.10", 2026-10-21), inspected on 2026-10-23.
Repository license: MIT, copyright (c) 2026 AndCode contributors.
No source code is copied into this repository.

This is a focused source review of the runtime, lifecycle, API, security
and test areas that could inform the G1 services. It is not a line-by-line
audit of every AndCode feature, and AndCode was not built or run during
this review. Nothing here unblocks G0 or changes D1, D4 or D5.

## Baseline

- Native Android app in Kotlin with Jetpack Compose. The application lives
  in one `app` module (plus a benchmark module); there is no equivalent of
  the ten `core/*-api` boundaries.
- `compileSdk 35`, `minSdk 26`, `targetSdk 35`, version `1.2.27`.
- 296 Kotlin source files, 185 test files and about 1,420 `@Test`
  declarations. Tests use JUnit 4, MockWebServer and `kotlinx-coroutines-test`.
- Runtime is downloaded on first use, not bundled. It fetches OpenCode
  `1.18.5` and Alpine `3.24.1`; our pins are OpenCode `1.19.3` and
  Alpine `3.20.3`.
- Runtime manifest schema 1 declares a fixed port `4097`, ABIs
  `arm64-v8a` and `x86_64`, and runtime version `2026.07.25.1`.
- The server runs inside a PRoot/Alpine guest: AndCode has no Profile A
  implementation. It is therefore evidence for Profile B mechanics only,
  not for the D4 default.
- Executables and libraries are packaged through `jniLibs` with
  `useLegacyPackaging = true`; no executable is run from writable storage.
- A `specialUse` foreground service runs the runtime; the manifest also
  requests `WAKE_LOCK`, `MANAGE_EXTERNAL_STORAGE` and a broader permission
  set than our D2/SEC-3 target.
- Its minSdk 26 support shows the jniLibs launch route can ship to older
  devices, but that does not justify lowering our D2 floor.

## Findings that affect reuse

1. **Not a drop-in architecture.** The runtime, UI, DI and persistence
   layers are interleaved in one Compose app. Most runtime classes take
   `Context`, Koin/application dependencies or Compose state. Lifting
   them into `core/*` would be a rewrite, not a package move.
2. **No native PTY.** `TerminalViewModel.executeCommand()` runs a
   one-shot shell command through `LocalRuntimeCommandRunner` with a
   15-second default (30 seconds from the terminal) and a 4,000-character
   output cap. It is line-based, has no `forkpty`, `TIOCSWINSZ`, resize or
   signal path, and cannot satisfy TERM-1 to TERM-5.
3. **No complete workspace or Git service.** The app relies on OpenCode's
   file APIs and shell commands. It has Git clone helpers, not the D8/D9
   native operations and credential model.
4. **Weaker local-secret storage.** `LocalServerAuth` generates a 32-byte
   secret and stores it in an app-private file. Basic auth uses username
   `opencode`, which is compatible with our server contract, but plaintext
   file storage does not meet D7's Android Keystore wrapping.
5. **Fixed loopback port.** Port `4097` is compiled into the runtime
   manifest and client configuration. D7 requires a free port selected at
   start and a listener inventory.
6. **Download-first runtime.** `LocalRuntimeInstaller`,
   `VerifiedRuntimeDownloader` and `LocalRuntimeReleaseClient` implement
   first-launch and update downloads. D5 requires the runtime to ship in
   the release with no first-launch download, so that path must not be
   adopted.
7. **OpenCode 1.18.5, not 1.19.3.** Its API, SSE and session edge cases
   are useful test material, but behavior must be rechecked against the
   pinned 1.19.3 contract before any port.
8. **Android API level differs.** AndCode targets API 35 and supports
   API 26; our D2 is minSdk 31 and targetSdk 36. Its specialUse-service and
   process code can inform D11, but its manifest and storage permissions
   cannot be copied.
9. **License scope.** The repository `LICENSE` is MIT, but that does not
   automatically cover bundled binaries, fonts, generated assets or
   third-party source. Any copied file needs a provenance check and an
   entry in `docs/licenses/NOTICES.md`.

## Reuse matrix

### Adopt after G0, with adaptation

| Area | AndCode evidence | Why it fits | Conditions |
| --- | --- | --- | --- |
| Runtime manifest verification | `runtime/local/LocalRuntimeManifest.kt`, `LocalRuntimeManifestTest.kt` | Strict manifest fields, HTTPS URL and sha-256 validation, ABI selection and version checks map directly to RT-3 and D5 | Rebuild against our schema and pinned artifacts; reject unknown majors; retain our own manifest source |
| Native-library packaging | `app/build.gradle.kts` (`useLegacyPackaging`, generated `jniLibs` source), `scripts/prepare_android_runtime_native_libs.py` | Concrete proof of the D3 pattern: PRoot, loader and helpers installed as native libraries, with executable and SONAME references renamed for `jniLibs` | Compare its 16 KB alignment handling with our `native/` scripts; do not copy generated binaries |
| PRoot command construction | `runtime/local/GuestProotCommand.kt`, `EmbeddedCommandSuite.kt` | Handles `--kill-on-exit`, `--link2symlink`, guest binds, `PROOT_TMP_DIR`, `PROOT_LOADER` and jniLibs extraction | Profile B only; constants and paths must follow our runtime manifest and D4 decision |
| Process launch and cleanup | `runtime/local/LocalRuntimeProcessLauncher.kt:41` (launch), `:164` (terminate), `:241` (process tree), `:314` (environment) | Useful patterns for environment isolation, child-before-parent termination, root-PID discovery and bounded log truncation | Replace its `android.os.Process`/`/proc` assumptions where they conflict with our supervisor; no auto-restart |
| Runtime activation and rollback | `LocalRuntimeInstaller.kt:374` (extract), `LocalRuntimeUpdater.kt`, `LocalRuntimeUpdaterTest.kt` | Staging, atomic replacement and interrupted-update recovery are well tested | Only if a future bundled-runtime update path is approved; not part of D5 v1 |
| Supervisor backoff and crash-loop policy | `LocalRuntimeService.kt:206` (`RestartBackoff`), `RestartBackoffTest.kt` | Bounded exponential backoff with crash counting and an uptime reset | It has no terminal crash-loop state; LC-3 needs a 5-failures-in-5-minutes breaker. Use the structure, not its timings |
| Idle shutdown | `LocalRuntimeService.kt:192` (`shouldStopIdleRuntime`), `:252` (`IdleStopTracker`), `IdleStopTrackerTest.kt`, `IdleStopSequenceTest.kt` | Separates "busy" from "idle" and stops only after a sustained quiet period | Our default is 10 minutes, not 15; use our `RuntimeWorkTracker` semantics and test names |
| Runtime watchdog | `LocalRuntimeWatchdog.kt`, `LocalRuntimeWatchdogTest.kt` | Consecutive-observation threshold, resets on healthy/transitional states, never restarts broken or unsupported states | Adapt to `ServerState`; threshold and state names are ours |
| Work tracking and wake locks | `core/runtime/RuntimeWorkTracker.kt`, `LocalRuntimeAccessCoordinator.kt`, their tests | Reference-counted busy work and serialized access are close to D11/LC-4 needs | Replace its Koin/Context wiring; prove the wake lock is released on every exit path |
| SSE event parsing and reconnect | `core/api/OpenCodeEventParser.kt`, `OpenCodeApiClient.kt:521-535`, `OpenCodeApiClientTest.kt` | Handles `global/event` with fallback to `event`, `retryWhen`, `server.connected`, `message.part.delta`, permission and question events | Our TypeScript gateway already implements S4; port only missing native-parser and fault-injection cases, and revalidate against 1.19.3 |
| Secret redaction | `core/security/SecretRedaction.kt`, `core/diagnostics/CrashReportSanitizer.kt` | Covers bearer/authorization/cookie values, `sk-` keys and GitHub tokens; useful for SEC-2 | Keep our allowlist and redaction tests; never let redaction become the only secret control |
| URL and connection payload rules | `core/security/OpenCodeUrl.kt`, `ConnectionQrPayload.kt` | HTTP(S) parsing, cleartext-host policy and connection URI/QR parsing with password redaction | Adapt to our free port, remote-profile rules and SEC-6 |
| Test corpus | `runtime/local/*Test.kt`, `core/api/*Test.kt` | Edge cases around malformed manifests, symlinked instruction paths, partial downloads, process ordering, reconnect replay and form/question mapping | Tests are MIT but depend on AndCode types; rewrite against our ports and fixtures |

### Reference only, do not transplant

| Area | Reason |
| --- | --- |
| `OpenCodeApiClient.kt` / `OpenCodeV2ApiClient.kt` / `ProtocolDetectingOpenCodeApi.kt` | Large client built around AndCode models and exception flow; our gateway uses `ApiResult<T>`, typed ops and per-op timeouts. Reuse test cases, not the client |
| `LocalRuntimeInstaller.kt`, `VerifiedRuntimeDownloader.kt`, `LocalRuntimeReleaseClient.kt` | Implements the download-first supply chain that D5 rejects. Its checksum, range-resume and atomic-replace tests remain useful references |
| `LocalServerAuth.kt` | Basic-auth shape is compatible, but plaintext app-private storage fails D7's Keystore requirement |
| Compose screens and navigation | Our UI is React/TypeScript in a WebView; UI code cannot move across the boundary |
| `CodexSandboxLauncher`, `ClaudeSandboxLauncher`, `AntigravitySandboxLauncher` | Useful examples of guest environment setup, but they target other agents and are outside the OpenCode-only v1 |
| Git clone helper | Clone-only; not the D8/D9 workspace, atomic-write, trash or credential-helper design |

### Reject

- Executing the runtime from app data or the download cache.
- Fixed port `4097` and a single global origin.
- First-launch runtime download and self-update as the v1 delivery path.
- Plaintext local server secrets.
- Line-based terminal presented as a PTY.
- AndCode's manifest permissions, all-files access and exported services.
- Its single-module architecture, Koin graph or Compose state as the
  starting point for our core modules.
- Any OpenCode 2.x-only behavior before D5/S6 decide the pin.

## Questions this review answers

| Question | Answer |
| --- | --- |
| Does AndCode prove Profile A works? | No. Only Profile B/PRoot is implemented. D4 remains open. |
| Does AndCode provide the native PTY required by D10? | No. It runs non-interactive shell commands. |
| Does AndCode meet D7? | No. Random secret and Basic auth shape only; storage is plaintext and the port is fixed. |
| Does AndCode ship the runtime offline? | No. It downloads OpenCode and Alpine on first use. |
| Can its process supervisor be used as-is? | No. Backoff, idle and state semantics differ; only the structure and tests are reusable. |
| Can its UI be reused? | No. It is Compose; our UI is React in a WebView. |
| Is the MIT license enough to copy a file? | The repository license permits reuse with notice, but each file and bundled asset still needs provenance and third-party review. |

## Recommended use

Treat AndCode as a high-value reference implementation for D3/D4 process
packaging, D11 lifecycle policy and STR-5 event edge cases, and as a
source of test scenarios. Do not wire it into `app` or `core/*` before G0.
If any file is adapted later, record the source commit, retain the MIT
notice and add the attribution to `docs/licenses/NOTICES.md`.

## Inspected areas

- Build and manifest: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
  `README.md`, `LICENSE`.
- Runtime packaging: `scripts/prepare_android_runtime_assets.py`,
  `scripts/prepare_android_runtime_native_libs.py`, `runtime/` manifest.
- Runtime lifecycle: `runtime/local/LocalRuntimeInstaller.kt`,
  `LocalRuntimeManager.kt`, `LocalRuntimeService.kt`,
  `LocalRuntimeWatchdog.kt`, `LocalRuntimeProcessLauncher.kt`,
  `EmbeddedCommandSuite.kt`, `GuestProotCommand.kt`,
  `LocalRuntimeCommandRunner.kt`, `LocalRuntimeAccessCoordinator.kt`,
  `core/runtime/RuntimeWorkTracker.kt`, `LocalServerAuth.kt`.
- API and security: `core/api/OpenCodeApiClient.kt`,
  `OpenCodeEventParser.kt`, `ProtocolDetectingOpenCodeApi.kt`,
  `OpenCodeV2ApiClient.kt`, `core/security/SecretRedaction.kt`,
  `OpenCodeUrl.kt`, `ConnectionQrPayload.kt`,
  `core/diagnostics/CrashReportSanitizer.kt`.
- Terminal and tests: `feature/workspace/TerminalViewModel.kt`,
  `runtime/local/*Test.kt`, `core/api/*Test.kt`.
