# G1 core contracts (interface-only)

Status: scaffolded and compiling 2026-10-23. A minimal Android app shell
is present; no runtime implementation, bridge handler, JNI code or WebView
transport is included.

The bridge wire envelope is versioned and additive-only. The service
interfaces are provisional until D4 (runtime profile) and D5 (version
pin) are frozen at gate G0.

## Module map

All source lives under `io.github.spectordev9.opencode.core.<module>`.

| Module | Responsibility | Project dependencies |
| --- | --- | --- |
| `core:common-api` | IDs, typed errors/results, timeout defaults | none |
| `core:runtime-api` | runtime state, verified artifacts, process spawning | `common-api` |
| `core:supervisor-api` | loopback server lifecycle, health and backoff | `common-api` |
| `core:gateway-api` | typed HTTP operations, SSE events, health | `common-api` |
| `core:workspace-api` | path-scoped list/read/write/mkdir/rename/trash | `common-api` |
| `core:git-api` | guest Git status/diff/network/commit | `common-api` |
| `core:terminal-api` | native PTY open/write/resize/signal/close | `common-api` |
| `core:security-api` | Keystore-backed secrets, loopback auth, scrubbing | `common-api` |
| `core:diagnostics-api` | bounded event log and redacted report export | `common-api` |
| `core:bridge-api` | protocol v1 envelopes, command names, transport ports | `common-api` |

```text
common
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

- exactly the ten `core/*-api` modules are included;
- `common-api` has no project dependency and no core imports;
- every other module depends only on `common-api`, directly or by import;
- core source has no Android/AndroidX imports;
- every Kotlin file sits in the package matching its module directory; and
- only `common-api` may declare the coroutines dependency.

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

The 47 names are centralized in `BridgeCommandNames`:

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

## Deferred until after G0

- App service wiring, foreground service, WebView and WebMessagePort.
- JSON serialization and per-command payload DTOs.
- OkHttp/SSE gateway implementation and Basic-auth wiring.
- Android Keystore, log scrubbing and diagnostics storage.
- Native loader/PRoot process management and JNI `forkpty`.
- Dependency verification, release signing and dependency locking.
- Contract tests against fake implementations.

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
