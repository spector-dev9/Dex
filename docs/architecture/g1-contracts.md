# G1 core contracts and runtime boundary

Status: G1 implementation is in progress (owner direction 2026-10-27).
Core contracts and the minimal Compose Android shell compile. The app now
has an injectable production bridge handler for runtime, workspace, Git and
terminal commands, with app unit coverage against service fakes. The
app-local fake service harness remains test-only and is not device evidence.
The `core:runtime` JVM module validates the pinned manifest and installed
file layout. Its installer verifies both the archive and extracted
executable SHA-256 values, extracts into a staging directory, and publishes
with an atomic move. The `core:supervisor` JVM module now implements health
polling, backoff, crash-loop breaking, stop/restart, and state reporting
behind a `ServerLauncher` port. The `core:diagnostics` JVM module provides a
bounded in-memory event log that applies the `LogScrubber` before storage and
export. Android process launch, production launcher and authenticated-health
composition, gateway composition, persistent diagnostics export, JNI
`forkpty`, command payload DTOs, and bridge JSON serialization are still not
implemented.

G0 is not green. The owner has directed G1-G2 work to proceed while the
open G0 blockers (S1-B/S2/S3 production evidence, S6 2.x artifact, S9
compliance, baselines, D4) stay tracked as release blockers. D4 remains
provisional until its evidence clears.

The TypeScript bridge contract is retained as a parity fixture only. It is
not part of the Android UI or runtime architecture. Its envelope is
versioned and additive-only; the service interfaces are provisional until
D4 (runtime profile) and D5 (version pin) are frozen at gate G0.

## Module map

All source lives under `io.github.spectordev9.opencode.core.<module>`.

| Module | Responsibility | Project dependencies |
| --- | --- | --- |
| `core:common-api` | IDs, typed errors/results, timeout defaults | none |
| `core:runtime-api` | runtime state, verified artifacts, process spawning | `common-api` |
| `core:runtime` | manifest and runtime file validation; platform launcher port | `common-api`, `runtime-api` |
| `core:supervisor-api` | loopback server lifecycle, health and backoff | `common-api` |
| `core:supervisor` | process supervision, health polling, backoff, crash-loop breaker | `common-api`, `runtime-api`, `supervisor-api` |
| `core:gateway-api` | typed HTTP operations, SSE events, health | `common-api` |
| `core:workspace-api` | path-scoped list/read/write/mkdir/rename/trash | `common-api` |
| `core:git-api` | guest Git status/diff/network/commit | `common-api` |
| `core:terminal-api` | native PTY open/write/resize/signal/close | `common-api` |
| `core:security-api` | Keystore-backed secrets, loopback auth, scrubbing | `common-api` |
| `core:diagnostics-api` | bounded event log and redacted report export | `common-api` |
| `core:diagnostics` | bounded in-memory event log with scrubbed export | `common-api`, `diagnostics-api`, `security-api` |
| `core:bridge-api` | protocol v1 envelopes, command names, transport ports | `common-api` |

```text
common
  ├── runtime-api
  ├── runtime
  ├── supervisor
  ├── gateway
  ├── workspace
  ├── git
  ├── terminal
  ├── security
  ├── diagnostics
  └── bridge
```

`core:bridge-api` deliberately does not depend on the service modules. The
Android composition layer will implement `BridgeCommandHandler` and map
commands to the service ports after G0. This keeps the wire contract
independent of service implementation details.

Rules checked by `scripts/check-architecture.py`:

- exactly ten contract `core/*-api` modules plus `model`, `opencode-client`,
  `runtime`, `supervisor`, and `diagnostics` are included;
- `common-api` has no project dependency and no core imports;
- implementation modules depend only on their relevant core contracts;
- `runtime-api` remains free of implementation and serialization dependencies;
- core source has no Android/AndroidX imports;
- every Kotlin file sits in the package matching its module directory; and
- only modules that expose coroutine-based contracts or implementations may
  declare the coroutines dependency.

## Service ports

| Port | Key operations |
| --- | --- |
| `RuntimeManager` | `state`, `profile`, `ensureReady`, `spawn` |
| `ServerSupervisor` | `state`, `start`, `stop`, `restart` |
| `OpenCodeGateway` | typed `call`, `events`, `health` |
| `WorkspaceService` | `list`, `stat`, `readRange`, `write`, `mkdir`, `rename`, `move`, `trash`, `restore`, `search` |
| `GitService` | `status`, `diff`, `initialize`, `fetch`, `pull`, `branches`, `checkout`, `commit` |
| `PtyService` | `open`; sessions expose `events`, `write`, `resize`, `signal`, `close` |
| `SecretStore` | `put`, `get`, `delete` |
| `LoopbackAuth` | `provision`, `authorizationHeader`, `verify` |
| `DiagnosticsService` | `record`, `observe`, `export` |

All fallible service operations return `ApiResult<T>` with an
`ApiError`. Error codes and retryability match the bridge contract.

## Bridge protocol v1

The TypeScript source is `ui/src/bridge/protocol.ts`; the Kotlin mirror is
`core/bridge-api/.../BridgeProtocol.kt`. `scripts/check-bridge-contract.py`
compares version, error codes, retryability, timeout values, event topics
and envelope type strings from source. `:core:bridge-api:check` also runs
`CompiledContractParityTest`, which reflects over the compiled classes and
the Section 4.3 command list.

Envelope shapes:

```json
{"v":1,"id":"c-102","type":"cmd","name":"workspace.write","args":{}}
{"v":1,"id":"c-102","type":"res","ok":true,"value":{}}
{"v":1,"id":"c-102","type":"res","ok":false,"error":{"code":"E_CONFLICT","retryable":false}}
{"v":1,"type":"evt","topic":"session.event","seq":8812,"ts":1760000000000,"data":{}}
```

Kotlin enforces `v == 1` and the correct envelope `type` in every
constructor. A future version must add a new contract rather than mutate
v1.

### Errors

`E_RUNTIME_DOWN` (retryable), `E_AUTH`, `E_PATH_DENIED`, `E_CONFLICT`,
`E_TOO_LARGE`, `E_TIMEOUT` (retryable), `E_UNSUPPORTED_VERSION`,
`E_UPSTREAM` (retryable), `E_CANCELLED`.

`BridgeError.retryable` is derived from the code. A serializer must emit
the derived value so the JSON shape matches TypeScript.

### Timeouts

| Class | Default |
| --- | --- |
| Health | 2,000 ms |
| List/get | 10,000 ms |
| File read | 15,000 ms |
| `prompt_async` | 10,000 ms |
| Abort | 5,000 ms |
| SSE idle watchdog | 60,000 ms |
| Git network | 120,000 ms |
| Interactive shell/PTY | none |

### Event topics

`runtime.state`, `server.state`, `session.event`,
`permission.requested`, `terminal.data`, `workspace.changed`,
`diagnostics.alert`.

### Commands

The 47 names are centralized in `BridgeCommandNames`. The approved
`oc.md` shorthand writes `session.*` and omits `terminal.signal`; this set
is its explicit, additive expansion, with the same provisional G0
boundary recorded in Section 4.3 of the specification.

| Namespace | Count | Examples |
| --- | --- | --- |
| `runtime.*` | 5 | `runtime.status`, `runtime.exportDiagnostics` |
| `session.*` | 17 | `session.promptAsync`, `session.permissionReply` |
| `workspace.*` | 10 | `workspace.readRange`, `workspace.restore` |
| `git.*` | 8 | `git.checkout`, `git.commit` |
| `terminal.*` | 5 | `terminal.open`, `terminal.signal`, `terminal.resize` |
| `settings.*` | 2 | `settings.get`, `settings.set` |

Command arguments and event payloads are JSON-compatible maps in G1.
Per-command DTOs and the JSON adapter belong to the implementation phase;
they must not change the envelope shapes above.

## G1 implementation backlog

- [~] Injectable app bridge handler for runtime, workspace, Git and terminal
      commands; session and settings commands remain open.
- [ ] Runtime installer integration and Android process launcher.
- [~] Server supervisor with backoff, crash-loop breaker, stop/restart and
      state reporting; production launcher, authenticated health and
      process-tree termination remain open.
- [ ] Production gateway composition using `OpenCodeHttpClient` and
      `SecureLoopbackAuth`, including SSE reconnect/resync.
- [~] Diagnostics storage/export and production log scrubbing; in-memory
      redacted storage/export and tests are implemented, persistence and the
      production scrubber remain open.
- [ ] JSON serialization and per-command payload DTOs.
- [ ] Foreground-service lifecycle and recovery behavior.
- [ ] Native loader/PRoot process management and JNI `forkpty`.
- [ ] G1 device acceptance (RT-1..RT-4, SEC-1/2, STR-1/5, LC-3, WS-1/2).
- [ ] Dependency verification, release signing and dependency locking.
- [ ] Comprehensive contract tests against every bridge command.

The app-local fake services remain test fixtures, not production
implementations or device-acceptance evidence.

The TypeScript bridge is retained only as a protocol-parity fixture during
the scaffold phase. It is not an Android UI or a WebView transport; the
Android shell and planned user screens are native Kotlin and Jetpack Compose.

## Validation

```sh
python3 scripts/check-architecture.py
python3 scripts/check-bridge-contract.py
python3 -m py_compile scripts/*.py
npm run typecheck && npm test
./gradlew --no-daemon -PskipApp=true coreCheck
python3 scripts/check-provisional.py
```

`./gradlew` pins Gradle 8.13 with the distribution sha-256, and the build
uses a Java 21 toolchain. A clean build passed locally on 2026-10-23. The
`kotlin` CI job repeats it on Temurin 21 and uploads the compiled contract
JARs plus `build/reports/g1/provisional-members.txt`. The `android` CI job
installs API 36 and Build Tools 36.0.0, then assembles debug and release
APKs. CI run `39959198949` passed all three jobs on 2026-10-23 and
published both artifacts.
