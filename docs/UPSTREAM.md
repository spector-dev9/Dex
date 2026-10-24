# Upstream tracking

The upstream remote is `https://github.com/yuga-hashimoto/and-code.git`.
Its `main` branch resolved to `f28d8d93f82b10ddfb48da44d787192f87ed8360`
when this record was created. No changes from that upstream have been copied
as part of this update.

## Known divergences

| Area | This repository | Upstream at recorded revision |
| --- | --- | --- |
| Module boundaries | Pure Kotlin contracts and extracted implementations are split into `core/*` Gradle modules. | Runtime implementation, including `LocalRuntimeManager`, `LocalRuntimeService`, and `LocalRuntimeTarget`, is under the Android app source tree and uses app-specific types. |
| OpenCode contract | Client integration is pinned to OpenCode 1.19.3 and its checked-in OpenAPI snapshot. | Upstream runtime/client code is coupled to upstream API and model types; compatibility with the pinned 1.19.3 contract has not been established. |
| Runtime implementation | `core/runtime-api` defines the current boundary; `core/runtime` implementation has not yet been extracted. | Local runtime installer, manager, process lifecycle, and Android service sources exist in `app/src/main/.../runtime/local`. |

## Cherry-pick and sync policy

- Record the upstream commit being compared before importing a change.
- Review its dependencies against the current module boundaries and pinned API
  contract; do not copy app-coupled code directly into a core module.
- Port only compatible changes, preserving contract tests and the debug-build
  and device-smoke-test gates.
- Update this table with the resulting divergence or convergence and the
  commit(s) involved.

## Android candidate reuse audit

Audited on 2026-10-24 at immutable revisions. A repository's README, test
count, or GitHub license badge is not treated as proof of production
correctness. No candidate application or binary was imported.

| Candidate component and exact source paths | License and history | Local counterpart and compatibility | Decision | Adaptation effort vs. implementing locally | Correctness tests |
| --- | --- | --- | --- | --- | --- |
| [turanmertkaraca-bit/opencode-android](https://github.com/turanmertkaraca-bit/opencode-android/tree/e6cb13442d9371947237c326e0ee68a5b9b3c0c1): `app/src/main/java/ai/opencode/app/ServerService.java`, `Resilience.java`, `RunHub.java`, `JsonStream.java`, `DiagnosticsActivity.java`; JVM tests under `app/src/test/java/ai/opencode/app/` | No `LICENSE` file and GitHub reports no license. Revision `e6cb13442d9371947237c326e0ee68a5b9b3c0c1`; last pushed 2026-09-23; 1 star, 0 forks at audit. README describes OpenCode 1.18.25, Debian 12, zero runtime dependencies, and Robolectric/JVM tests. | This project uses Kotlin service boundaries, pinned OpenCode 1.19.3, Alpine + PRoot, and an authenticated OkHttp/SSE client. The candidate uses a Java app-specific server service and a Debian/native-executable model; its arm64 payload and `targetSdk 28` exec workaround conflict with our ABI/target and W^X decisions. Its README also describes a checked-in persistent debug keystore, a signing-integrity concern. | **REJECT** for code reuse. Runtime supervision, reconnect, bounded JSON, and diagnostics are not independently compatible with our process or guest model, and there is no license grant. | Not legally adaptable; clean implementation against our existing contracts is multi-day. | Existing supervisor/client tests plus crash/restart and bounded-stream tests on host and device; candidate Robolectric tests do not establish our PRoot or ABI behavior. |
| [hjinlabs-netizen/opencode-android](https://github.com/hjinlabs-netizen/opencode-android/tree/1911ea29715d1ee3ca02742bd66ddde8dee759e9): `app/src/main/java/com/anomalyco/opencode/data/remote/ResponseSizeGuard.kt`; `app/src/test/java/com/anomalyco/opencode/data/remote/ResponseSizeGuardTest.kt` | MIT, `Copyright (c) 2026 hjinlabs-netizen`; preserve the notice and MIT terms. Revision `1911ea29715d1ee3ca02742bd66ddde8dee759e9`; last pushed 2026-09-23; 2 stars, 0 forks at audit. The guard has dedicated tests for size limits and streaming reads. | At audit time, `OpenCodeHttpClient.execute` buffered every HTTP body with `ResponseBody.bytes()`. The candidate implementation is Ktor-specific, so only its bounded-read and endpoint-budget approach is adapted to our existing OkHttp module. Its large-message budget is retained at 16 MiB; our general JSON budget is 8 MiB and health is 1 MiB. These are provisional safeguards, not a return to the removed 5 MiB gateway cap; SSE remains a separate unbounded stream. | **ADAPT**; see the implementation and MIT notice below. | Small (about 0.5–1 day) versus about 1–2 days to design and test the same guard from scratch. | Verify exact-limit reads, known and absent/underreported `Content-Length`, over-limit typed errors, regular successful decoding, non-success body disposal, and SSE larger than JSON limits. Recheck budgets against device memory evidence before treating them as final. |
| Same MIT candidate: `app/src/main/java/com/anomalyco/opencode/data/remote/stream/OpenCodeStreamClient.kt`, `SseEventTransport.kt`; tests in `app/src/test/java/com/anomalyco/opencode/data/remote/stream/` and `app/src/androidTest/java/com/anomalyco/opencode/data/remote/stream/` | Same MIT terms and snapshot as above. JVM stream tests and Android real-socket SSE tests are present; these are candidate evidence, not tests run on this project. | Our `OpenCodeHttpClient.events` already provides Basic auth, typed event decoding, reconnect backoff, and cancellation through OkHttp EventSource. Candidate uses Ktor and an application-scoped stream supervisor. Replacing it would duplicate transport and lifecycle ownership. | **REFERENCE ONLY** for test scenarios and cancellation/reconnect semantics; no code imported. | Medium (about 2–4 days) to port a supervisor versus similar effort to extend our existing client only if a demonstrated gap is found. | Keep current client tests; add cancellation, retry, malformed-event, and real-socket coverage only where the current contract requires it. |
| Same MIT candidate: `app/src/main/java/com/anomalyco/opencode/data/repository/SessionRepositoryImpl.kt`, `app/src/main/java/com/anomalyco/opencode/ui/chat/ChatViewModel.kt`, `app/src/main/java/com/anomalyco/opencode/ui/session/SessionListScreen.kt`, and associated `app/src/test/java/com/anomalyco/opencode/` tests | Same MIT terms and snapshot as above. Compose, Hilt, Ktor 3.1.3, and Kotlin 2.2-era dependencies; app targets SDK 36 and min SDK 26. | Our Compose app is deliberately a minimal shell and its data boundary is the pure-Kotlin `core/opencode-client`. Candidate UI is a remote-server client and assumes its Hilt/Ktor repositories; importing it would add dependencies and couple presentation to a second service graph. No native runtime, Alpine/PRoot, or PTY is supplied. | **REFERENCE ONLY** for interaction and test patterns; no UI or data layer imported. | Medium/high (several days) to adapt; implementing within our existing app boundary is comparable and avoids a second DI/network stack. | Compose/ViewModel tests against our service contracts, followed by Android build and device acceptance. |
| Same MIT candidate: `app/src/main/java/com/anomalyco/opencode/data/settings/KeystoreAesGcmCipher.kt`, `ConfigVault.kt`; `app/src/androidTest/java/com/anomalyco/opencode/data/settings/KeystoreVaultAndroidTest.kt` | Same MIT terms and snapshot as above; JVM vault tests and Android Keystore instrumentation tests exist. | We have a `SecretStore` API, but Android Keystore-backed app wiring is not implemented. The candidate's vault is useful evidence, but its storage format and API do not match `SecretRef`/`ApiResult`; migration, alias lifecycle, and device tests would be required. | **REFERENCE ONLY** in this audit; defer adaptation until the G0 secret-store integration is eligible and the Android instrumentation path is available. | Medium (about 1–3 days) versus about 2–4 days to implement and validate a contract adapter locally. | Keystore round-trip, alias/key invalidation, tamper detection, secret clearing, and backup/restore behavior on supported Android devices. |
| [scisammy/opencode-android](https://github.com/scisammy/opencode-android/tree/700489dc5b19447cdf282e427b6ff4748d73844b): tracked implementation is `app/src/main/java/com/ubuntuterminal/MainActivity.kt`; README additionally claims `TerminalActivity`, `TerminalService`, `TermuxJNI.java`, and a PTY C helper | No `LICENSE` file and GitHub reports no license. Revision `700489dc5b19447cdf282e427b6ff4748d73844b`; last pushed 2026-07-21; 0 stars, 0 forks at audit. Actual tracked tree does not contain the README's claimed terminal/JNI files or a test suite. | README claims Ubuntu 24.04 + PRoot and terminal support, but the checked-in activity is a basic command/download screen; it does not establish interactive PTY behavior. Build uses Kotlin 1.9.21, compile SDK 34, min SDK 24 and AppCompat/Material, with no demonstrated ABI or instrumentation coverage. Our plan requires Alpine and native forkpty. | **REJECT** for code reuse: missing license grant, incomplete claimed implementation, different distribution, and stale toolchain. | No adaptation. Implement the planned native PTY and runtime against local contracts. | PTY input/output, resize, exit status, signal/process-tree cleanup, ABI/page-size, and target-device tests are required for any implementation. |

### Recommended order

1. Complete focused client tests/build and device-memory measurements for the
   bounded JSON-body read now adapted in `core:opencode-client`, keeping SSE
   outside that buffering path and preserving useful payloads above 5 MiB.
2. Continue the existing authenticated SSE/client integration; use the second
   candidate's test cases as references rather than introducing a Ktor stream
   stack.
3. When the G0 secret-store gate permits, implement an Android Keystore adapter
   for the existing `SecretStore` contract and use the candidate's instrumentation
   cases as a checklist.
4. Implement runtime supervision, crash diagnostics, and native PTY only against
   the existing runtime/supervisor/diagnostics/terminal contracts. None of the
   audited repositories supplies a licensed, compatible implementation.
5. Build Compose feature UI within this project's existing app module after
   service contracts are ready; do not import a remote-client dependency graph.

The response budgets above are deliberately endpoint-specific and larger than
the removed legacy 5 MiB cap. They remain provisional until device memory and
large-session acceptance measurements are recorded in `docs/runtime-matrix.md`
and `docs/traceability.md`.
