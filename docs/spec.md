# OpenCode Mobile: Audit and Technical Specification (v0.1)

The owner-approved `oc.md` (2026-10-23, 27,445 bytes, SHA-256
`77499b1bf2474c9e7391a48b10a5ec117e056e8367d6c6192c794db6911ef25a`)
is the source of record. This tracked copy mirrors it; the only
intentional divergence is the typed expansion of the command list in
Section 4.3, recorded there.

Draft of October 23, 2026. Inputs: the Vision & Technical Blueprint; the spector-dev9/Test README and AUDIT-REPORT.md (dated October 22, 2026); OpenCode's published server docs; public Termux and Android documentation. I read the README and audit report, not the source code. Items tagged **\[verify\]** need a code read or a device test. All numeric targets are proposals, to be ratified at gate G0 against measured baselines.

## 1. Audit of the blueprint

**Verdict.** The direction and the security principles are sound. But the blueprint treats runtime feasibility as open, and the evidence says it is mostly settled: the existing app already runs the OpenCode server on-device, and three independent community ports exist. The real risks are an unauthenticated loopback server, the distribution and policy decision, background execution, and gaps in OpenCode's own API. Recommendation: re-architect the native layer and keep the tested UI logic, rather than rebuilding everything.

### 1.1 What the evidence changes

| # | Blueprint assumption | Evidence | Consequence |
| --- | --- | --- | --- |
| F1 | Runtime feasibility must be proven | Audit report: a pinned release (verified on OpenCode 1.19.3) runs from the native library dir via a musl loader, with PRoot, libtalloc, libandroid-shmem and an Alpine 3.20 rootfs; Node 22 and npm work in the guest. Community ports use three techniques: glibc-runner wrapper, musl loader plus the official musl binary (about 200 MB), and a source-built Android Bun. | Phase 2 becomes a selection benchmark, not a feasibility study. |
| F2 | Polling is a backend limit | README: polling exists because the native HTTP bridge does not expose SSE. OpenCode serves /event and /global/event. | Fix in the app transport (D6). No upstream dependency. |
| F3 | There is no PTY | README says the terminal uses the session shell endpoint; the audit report mentions a PtySocketPlugin and a terminal API with custom commands; OpenCode's published API lists no PTY routes. | Documentation drift. Do not build on an undocumented endpoint (D10, S5). |
| F4 | Backend may lack file operations | Published API: list, read, find, status only. The existing app writes through single-file patches, so no folders, rename, move, delete or empty files. | Upstream will not supply these. Own a native WorkspaceService (D8). |
| F5 | Loopback is safe by default | Any app with INTERNET permission can connect to 127.0.0.1. README: the app stores no OpenCode credentials and the allowlist is client-side. OpenCode supports basic auth through OPENCODE\_SERVER\_PASSWORD. | **Highest severity.** Unless the server is started with a password **\[verify in LocalServerManager\]**, any local app could reach the shell and file routes. See D7. |
| F6 | Release is a signing and networking task | Android 10+ blocks execve of files in the app data dir for targetSdk 29 and up; Termux stayed on targetSdk 28 and left Play. A public report shows Play rejecting uploads below API 36. The existing app downloads the OpenCode release on first launch. | Decide distribution first: runtime delivery, downloaded-executable policy, F-Droid source-build rules (D1, S9). |
| F7 | Mobile behavior can be validated late | Audit report: no device measurements were possible; the always-on server is the main heat source. Android 12+ limits child processes; Android 15 caps dataSync foreground services. | Idle shutdown and thermal limits become requirements (LC-4, LC-6). |
| F8 | The stack can be chosen once | OpenCode v2 is announced; the repo is now anomalyco/opencode; community ports track both 1.x and 2.0.x. The existing app pins 1.19.x. | Pin policy, contract tests, drift job (D5, S6). |
| F9 | Agent, terminal and shell share an environment | Audit 2.2: agent, terminal and Alpine shell were three filesystems; bind mounts fixed the terminal and Alpine panel. Where the agent's own bash tool executes is not stated. | Choose the runtime profile explicitly (D4, S3). |
| F10 | All listed problems are open | Build/Plan switching, Git init/fetch/pull/branches, the Alpine shell with project bind-mount, tool cards and error boundaries exist, with 191 tests passing. | Keep a regression map (section 7.8) so the rebuild loses none of it. |

### 1.2 Gaps in the blueprint

- No supported OS, device or ABI matrix.
- No version-pin, upgrade or data-migration policy, including migration from the existing app's session store.
- No distribution or licensing plan (PRoot is GPLv2; Alpine packages vary; source-offer duties).
- No idle, battery or thermal policy.
- No threat model: other apps on the device, hostile repository contents, prompt-injected agent actions, token handling.
- No privacy stance: provider credentials in OpenCode's auth.json, backups, crash reporting.
- No statement of what is native and what is WebView, or why.
- Gate criteria without numbers. Section 7 adds them.

### 1.3 Reuse triage

| Disposition | Items |
| --- | --- |
| Keep | React 19 + Vite UI; pure-logic libraries (agent mode, tools, todos, workspace normalizers, markdown, navigation) and their tests |
| Port | Error boundaries, ToolCard, TodoPanel, appearance settings, session import/export |
| Replace | CapacitorHttp transport and client-side allowlist; LocalServerManager; download-on-first-launch; patch-based file writes; the ad-hoc Git plugin |
| Remove | Capacitor, after milestone M2 |

## 2. Decisions

| ID | Decision | Rationale | Reversal trigger |
| --- | --- | --- | --- |
| D1 | Distribute first through GitHub Releases and a self-hosted F-Droid repo (Obtainium compatible). Google Play only after S9. | Play restricts downloading executable code; F-Droid's main repo builds from source and may reject prebuilt upstream binaries **\[verify\]**. | S9 finds a compliant bundling route. |
| D2 | minSdk 31, targetSdk 36, arm64-v8a only in release; x86\_64 debug builds for emulator CI. | Community reports Bun binaries failing on Android 10 and 11 (seccomp, statx). Play requires a current target. | S1 passes cheaply on Android 10/11. |
| D3 | Never execve a file from writable storage. Ship the loader, PRoot and helpers as jniLibs with extractNativeLibs=true; guests are mapped by the loader or PRoot. All ELF segments 16 KB aligned. | W^X for targetSdk 29+; 16 KB page devices. | None. |
| D4 | **Profile A (default):** official musl OpenCode binary launched directly through the bundled loader; the agent's shell is redirected into the Alpine/PRoot guest by a wrapper, so agent, PTY and Git share one filesystem. **Profile B:** the whole server runs inside PRoot. | PRoot adds ptrace cost to every syscall; the server is database and filesystem heavy. | Rules in S2 and S3. |
| D5 | Exact version pin (start at 1.19.x, evaluate 2.0.x), sha-256 verified, OpenAPI snapshot in the repo, unknown major versions refused. The runtime ships inside the release; no first-launch download. | Contract stability and supply chain. | None. |
| D6 | Kotlin gateway (OkHttp plus SSE) to 127.0.0.1. The WebView never talks to the server; it receives typed events over WebMessagePort. | Removes polling, the 5 MiB response cap and the SSE gap. | None. |
| D7 | Per-install random 256-bit secret as OPENCODE\_SERVER\_PASSWORD; free port chosen at start; bind 127.0.0.1; secret wrapped by Android Keystore; only the gateway holds it. | Loopback is reachable by other apps. | If OpenCode adds a unix-socket listener, prefer it. |
| D8 | Native WorkspaceService owns create, rename, move, delete, atomic write and range reads. Canonical-path checks, no symlink escape, mtime+size+hash preconditions, delete goes to an in-app trash. Projects live in app-private storage; shared storage only by import/export copy, or MANAGE\_EXTERNAL\_STORAGE in the sideload build. | SAF URIs are not POSIX paths, so PRoot and git cannot use them. | None. |
| D9 | Git is the Alpine git binary in the guest. Credentials through a GIT\_ASKPASS helper backed by Keystore, never in remote URLs. HTTPS only in v1. | Fidelity: hooks, submodules. | None. |
| D10 | Native PtyService (JNI forkpty, TIOCSWINSZ, process-group kill) launching the guest shell through PRoot, rendered with xterm.js. Server PTY routes, even if present, are not used. | Must work when the server is down; no undocumented API dependency. | S8 input failures: switch to a native terminal view. |
| D11 | Foreground service type specialUse; wake lock only while a session is busy; idle shutdown (default 10 min); crash-loop breaker; detect exit 137 and guide the user on child-process restrictions. | Android 15 dataSync caps; thermals. | S7. |
| D12 | Keep React, TypeScript and Vite; CodeMirror 6 editor; xterm.js; WebViewAssetLoader. Native owns everything privileged. | Markdown, diff and editor ecosystems. | None. |
| D13 | Gradle Kotlin DSL with a version catalog, pinned toolchains, reproducible-build check, CycloneDX SBOM, separate .debug applicationId, CI signing. | Reproducible release. | None. |

The blueprint's goal of not being merely a webview wrapped in an APK is met by what surrounds the WebView (runtime, supervisor, gateway, workspace, PTY, security), not by replacing the WebView.

## 3. Architecture

The six blueprint layers map to modules: UI is ui/; the application service layer is core:bridge; the runtime manager is core:runtime and core:supervisor; the OpenCode backend is the pinned binary behind core:gateway; workspace and terminal services are core:workspace, core:git and core:terminal; security and persistence are core:security and core:diagnostics.

Dependency rules, enforced by Gradle module boundaries and architecture tests: ui depends only on the bridge contract; gateway knows nothing about the runtime profile; workspace knows nothing about OpenCode.

```kotlin
interface RuntimeManager {
  val state: StateFlow<RuntimeState>        // Absent, Installing, Ready, Failed(reason)
  suspend fun ensureReady(): Result<RuntimeInfo>   // verifies sha-256 against manifest
  val profile: RuntimeProfile               // A_DIRECT or B_PROOT
  fun spawn(spec: ProcessSpec): ManagedProcess     // never execve from writable storage
}
interface ServerSupervisor {
  val state: StateFlow<ServerState>         // Stopped, Starting, Healthy(version), Degraded, Backoff(until), CrashLoop
  suspend fun start(); suspend fun stop(graceMs: Long = 3000); suspend fun restart()
}
interface OpenCodeGateway {
  suspend fun <T> call(op: Op<T>): ApiResult<T>             // typed ops, per-op timeout policy
  fun events(directory: String? = null): Flow<ServerEvent>  // SSE with reconnect and resync
}
interface WorkspaceService {
  suspend fun list(path: String): List<Entry>
  suspend fun readRange(path: String, offset: Long, length: Int): Chunk
  suspend fun write(path: String, data: ByteArray, expect: Precondition): WriteResult  // temp file, fsync, rename
  suspend fun mkdir(path: String); suspend fun rename(from: String, to: String)
  suspend fun trash(path: String): TrashId; suspend fun restore(id: TrashId)
}
interface PtyService {
  fun open(spec: PtySpec): PtySession   // write(bytes), resize(cols, rows), signal(sig), close()
}
```

## 4. APIs

### 4.1 OpenCode endpoints used

From the published server docs; confirm against the pinned version's /doc spec (S5). Project-scoped requests pass a directory query that must match a discovered project, as the existing app does.

| Purpose | Endpoints |
| --- | --- |
| Health and version | GET /global/health |
| Events | GET /global/event or GET /event (first event is server.connected) |
| Sessions | GET, POST /session; GET, PATCH, DELETE /session/:id; GET /session/status; POST /session/:id/fork, /abort, /revert, /unrevert, /summarize; GET /session/:id/diff |
| Messages | GET /session/:id/message; POST /session/:id/prompt\_async (returns 204); POST /session/:id/command; POST /session/:id/shell |
| Permissions | POST /session/:id/permissions/:permissionID |
| Models, agents, config | GET /config/providers, /provider, /agent, /command; PATCH /config; PUT /auth/:id |
| Project and VCS | GET /project, /project/current, /path, /vcs, /file/status |
| File read and search | GET /file, /file/content, /find, /find/file, /find/symbol |
| Spec | GET /doc |

### 4.2 Capabilities OpenCode does not document, and who owns them

| Capability | Owner |
| --- | --- |
| File write, mkdir, rename, move, delete | WorkspaceService |
| Interactive PTY | PtyService |
| Git init, fetch, pull, branch, checkout, commit | GitService |
| Server lifecycle, logs, diagnostics | ServerSupervisor |
| Authentication of local clients | D7 |
| Live context-window metrics | Derived from message token fields and labeled last reported, never live |

### 4.3 Bridge contract (UI and native)

Envelope shapes: command {v:1, id:'c-102', type:'cmd', name:'workspace.write', args:{...}}; result {v:1, id:'c-102', type:'res', ok:false, error:{code:'E\_CONFLICT', retryable:false}}; event {v:1, type:'evt', topic:'session.event', seq:8812, ts:..., data:{...}}.

- **Commands:**
  - runtime.status, runtime.start, runtime.stop, runtime.restart,
    runtime.exportDiagnostics
  - session.list, session.get, session.create, session.update,
    session.delete, session.status, session.fork, session.abort,
    session.revert, session.unrevert, session.summarize, session.diff,
    session.messages, session.promptAsync, session.command,
    session.shell, session.permissionReply
  - workspace.list, workspace.stat, workspace.readRange, workspace.write,
    workspace.mkdir, workspace.rename, workspace.move, workspace.trash,
    workspace.restore, workspace.search
  - git.status, git.diff, git.init, git.fetch, git.pull, git.branches,
    git.checkout, git.commit
  - terminal.open, terminal.write, terminal.resize, terminal.signal,
    terminal.close
  - settings.get, settings.set
- **Events:** runtime.state, server.state, session.event (coalesced to at most one batch per 50 ms), permission.requested, terminal.data (binary over a message port), workspace.changed, diagnostics.alert.

> Reconciliation note: `oc.md` abbreviates the session family as
> `session.*` and lists `terminal.open`, `terminal.write`,
> `terminal.resize` and `terminal.close`. The list above is the typed
> expansion of that shorthand: 17 session proxies plus an additive
> `terminal.signal` command needed by D10 and TERM-2. No command in
> `oc.md` is removed. The addition remains provisional until G0, and
> bridge v1 stays additive-only.

### 4.4 Errors and timeouts

Error codes, each with a retryable flag: E\_RUNTIME\_DOWN, E\_AUTH, E\_PATH\_DENIED, E\_CONFLICT, E\_TOO\_LARGE, E\_TIMEOUT, E\_UNSUPPORTED\_VERSION, E\_UPSTREAM(status), E\_CANCELLED.

| Operation class | Default timeout |
| --- | --- |
| Health | 2 s |
| List and get | 10 s |
| File read | 15 s |
| prompt\_async | 10 s |
| abort | 5 s |
| SSE | No read timeout; 60 s idle watchdog (tune after S4) |
| Git network operations | 120 s |
| Shell and PTY | None; user cancels |

All values are configurable and every timeout is logged with its operation name.

## 5. Repository structure

```
opencode-mobile/
├─ app/                    manifest, activity, foreground service
├─ core/
│  ├─ runtime/             RuntimeManager, profiles A and B, manifest verification
│  ├─ supervisor/          ServerSupervisor, health, backoff, idle shutdown
│  ├─ gateway/             OkHttp client, SSE, auth, typed operations
│  ├─ workspace/           WorkspaceService, path policy, trash
│  ├─ git/                 GitService (guest git), credential helper
│  ├─ terminal/            PtyService, JNI forkpty
│  ├─ bridge/              command and event contract, WebMessagePort plumbing
│  ├─ security/            Keystore secrets, log scrubber, policy tests
│  └─ diagnostics/         logs, redaction, exportable report
├─ native/                 build scripts and patches: loader, proot, talloc, shm (16 KB aligned)
├─ runtime/                manifest.json (versions, sha-256), Alpine rootfs build, opencode fetch script
├─ ui/                     React + TypeScript + Vite
│  └─ src/features/{chat,files,git,context,terminal,settings}  src/bridge-client  src/lib
├─ contract/               pinned OpenAPI snapshot, SSE fixtures, contract tests, drift job
├─ tests/                  androidTest, device-farm scripts, Macrobenchmark perf harness
├─ docs/                   adr/, threat-model.md, runtime-matrix.md, release.md, licenses/
├─ tools/                  reproducible-build check, SBOM, license and secret scans
└─ .github/workflows/      ci, nightly-device, drift, release
```

## 6. Phase 2 spikes

| ID | Question | Pass rule |
| --- | --- | --- |
| S1 | Do Profiles A and B run the pinned musl binary on Tier A/B/C (Android 12 to 16, one 16 KB-page device)? Start, /global/health, one prompt round trip against a mock provider. | 100% of Tier A/B devices start. Tier C deviations documented. If A fails on any Tier A/B device, default to B. |
| S2 | A versus B: cold start, idle RSS, git status on a 10k-file repo, npm test through the agent. | B becomes default only if A fails S1 or S3. B is rejected as default if it is more than 2.5x slower than A on git status or npm test. |
| S3 | Does the agent's bash tool honor a SHELL override or config so its commands run in the guest? | pwd, node -v and PATH identical in agent shell and PTY. Otherwise default to B. |
| S4 | SSE semantics: heartbeat cadence, reconnect, ordering, duplicates, directory scoping. | A written resync algorithm; STR-5 fault injection passes. |
| S5 | Endpoint inventory: diff the pinned /doc against 4.1; PTY routes; --port and auth behavior; unix-socket support. | Inventory committed to contract/. |
| S6 | Does the contract suite pass on OpenCode 2.0.x? | Pin 2.x only if every used endpoint passes or needs a thin shim; otherwise pin 1.x with a drift job. |
| S7 | Background survival with child-process restrictions on and off; specialUse service; OEM battery savers (Samsung, Xiaomi, Oppo). | LC-2 met on all tiers, or per-OEM guidance shipped in diagnostics. |
| S8 | xterm.js versus a native terminal view for typing, extra keys, IME. | TERM-5 passes on Gboard; otherwise native view. |
| S9 | Licensing and distribution: GPLv2 duties for PRoot, Alpine package licenses, Play executable-code policy, F-Droid source-build rules. | Written decision for D1; compliance checklist in docs/licenses. |

## 7. Acceptance criteria

Reference devices: **Tier A** flagship, 8 GB or more, Android 15 or 16 (one with 16 KB pages); **Tier B** mid-range, 6 GB, Android 13 or 14; **Tier C** low-end, 4 GB, Android 12, aggressive OEM battery management. Percentiles are over at least 30 runs unless stated. After G0 ratification every threshold is release-blocking, and any performance metric may regress at most 10% against its recorded baseline.

### 7.1 Runtime

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| RT-1 | Works without Termux, offline | Clean install on Tier A/B/C in airplane mode, GET /global/health | Healthy on 3 of 3 devices |
| RT-2 | Startup | Cold (process dead to healthy), first run (extraction), warm (service alive to UI ready) | Cold at most 10 s on A/B and 20 s on C; first run at most 60 s; warm at most 2 s (p95) |
| RT-3 | Integrity and version | Artifact hashes against manifest at start; health version against pin | 100% match, otherwise refuse to start |
| RT-4 | No exec from writable dirs | Full E2E suite, scan logcat | 0 avc denials for execute\_no\_trans |
| RT-5 | 16 KB pages | llvm-readelf alignment check in CI; boot on a 16 KB-page device | All LOAD segments aligned to 16384 or more; boots |

### 7.2 Streaming

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| STR-1 | No polling while connected | Gateway request counter over a 10 min session | 0 periodic message-list requests; only on resync |
| STR-2 | Event-to-paint latency | Trace gateway receive to frame commit on Tier B | p95 at most 150 ms, p99 at most 300 ms |
| STR-3 | Smoothness | Macrobenchmark during a 60 s streamed reply with markdown and code | At most 5% janky frames |
| STR-4 | Cancel | Tap Stop to idle state | p95 at most 1 s; zero deltas after abort is acknowledged |
| STR-5 | Recovery | 500 fault-injected SSE drops | Reconnect within 3 s; final transcript equals GET /session/:id/message; no duplicate or missing parts |

### 7.3 Workspace and Git

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| WS-1 | Path safety | Fuzz 100k paths: .., absolute, symlink escapes and loops, NUL, unicode normalization, case variants | 0 operations outside the root; all return E\_PATH\_DENIED |
| WS-2 | Atomic writes | SIGKILL mid-write, 1,000 runs, 1 KB to 50 MB | File is always entirely old or entirely new (checksum) |
| WS-3 | Conflicts | External change between open and save | E\_CONFLICT every time; never a silent overwrite |
| WS-4 | Large files | 100 MB text file; 1 GB binary | First screen at most 1 s; peak extra memory at most 64 MB; binary never fully loaded; image decode bounded to 32 MB |
| WS-5 | Complete operations | 60-case matrix: create, rename, move, trash, restore for files and folders, including spaces, quotes, unicode, empty files | 100% pass |
| WS-6 | Git | Fixture repos: init, status, diff, fetch, pull, branch, checkout, commit; dirty-tree guards | 100% pass; token absent from .git/config, logs and process arguments |

### 7.4 Terminal

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| TERM-1 | Interactive programs | Scripted suite: vim edit and save, less, node REPL, python -i, a full-screen TUI, apk add with progress bar | 100% pass |
| TERM-2 | Signals and cleanup | Ctrl-C on a foreground job; close the session | Terminates within 500 ms (p95); exit status shown; 0 orphan processes |
| TERM-3 | Resize | 20 random rotations and resizes, compare stty size | Always matches |
| TERM-4 | Flood | yes piped to head -c 500M | Echo latency p95 at most 100 ms; memory growth at most 30 MB (scrollback bounded, default 10,000 lines) |
| TERM-5 | Input | Extra-key row (Esc, Tab, Ctrl, Alt, arrows), hardware keyboard, Gboard | Typing test passes with Gboard, else D10 fallback |

### 7.5 Lifecycle and performance

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| LC-1 | Rotation | 100 rotations during a streamed reply | No lost or duplicated content; no new server process |
| LC-2 | Background task | Screen off, 30 min, agent task running, Tier A/B/C | Completes in at least 19 of 20 runs per device; failures classified in diagnostics |
| LC-3 | Crash recovery | SIGKILL the server 50 times | Healthy within 10 s (p95); sessions intact; exactly one listener; crash-loop breaker after 5 failures in 5 min |
| LC-4 | Idle cost | Backgrounded, no busy session | Idle-running CPU at most 1% average over 10 min; server stops after the idle timeout; process-tree CPU is 0 afterward |
| LC-5 | Memory | 4 h soak on Tier A/B | PSS slope at most 5 MB/h after warm-up; 0 low-memory kills; peak within baseline plus 10% |
| LC-6 | Thermal | 30 min agent session | Thermal status below MODERATE for at least 90% of samples on Tier A/B; recorded in release notes |

### 7.6 Security

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| SEC-1 | Local clients | Instrumented second app (different UID) calls every documented route on every listening port | 100% rejected without the secret; only expected listeners exist |
| SEC-2 | Secrets | Scan APK, logcat, app logs, crash dumps and process arguments across a full E2E run | 0 findings; provider credentials mode 600; backups disabled |
| SEC-3 | Manifest | Lint the release build | debuggable false; only the launcher exported; cleartext allowed for 127.0.0.1 only; permissions limited to INTERNET, notifications, foreground service |
| SEC-4 | Privileged actions visible | Every OpenCode permission request is shown and answerable; agent file writes and shell calls appear in the transcript | 100% |
| SEC-5 | Supply chain | Pinned hashes, CycloneDX SBOM, osv-scanner, license check | 0 unfixed critical or high at release; GPL source offer published |
| SEC-6 | Remote profile (v1.1) | MITM proxy; TLS 1.2 or higher with validation | Fails closed; no cleartext path |

### 7.7 Release and quality

| ID | Requirement | Method | Threshold |
| --- | --- | --- | --- |
| REL-1 | Reproducible build | Two clean CI builds compared with diffoscope | Byte-identical unsigned APK, or a documented exceptions list |
| REL-2 | Upgrade | Install each prior release, update, compare state | Sessions, projects and settings intact; downgrade refused with a message |
| REL-3 | Signing | apksigner verify in CI | v2 and v3 signatures valid; separate .debug applicationId |
| REL-4 | Compatibility | Contract suite against the pinned OpenAPI; nightly drift job against latest upstream | 100% of used endpoints validate; drift opens an issue |
| QA-1 | Code quality | Coverage and lint | At least 80% line coverage in workspace, gateway, supervisor, security; 0 lint warnings; every existing test dispositioned (ported or replaced, with reason) |
| QA-2 | Devices | Nightly E2E on at least 3 physical devices | Release requires all green |

### 7.8 Regression map for known limitations

| Existing limitation (README) | Covered by |
| --- | --- |
| Polling only, no streaming | STR-1 to STR-5 |
| Exact loopback origin compiled into the app | D7, SEC-1; remote profile SEC-6 |
| Archived sessions filtered client-side | REL-4 contract test; keep the client filter until upstream adds a parameter |
| No folder create, rename, move, delete | WS-5 |
| Cannot create empty files; unsafe names restricted | WS-5, WS-1 |
| Binary files not rendered; files over 1,000,000 characters not opened | WS-4 |
| Token usage not live | Label rule in 4.2; contract test on token fields |
| Layout not validated on a physical device | LC-1, QA-2 |
| No interactive PTY | TERM-1 to TERM-5 |
| Debug-only builds | REL-1 to REL-3 |

## 8. Phases and gates

| Gate | Exit criteria |
| --- | --- |
| G0 (end of Phase 2) | S1 to S9 complete; baselines recorded on Tier A/B/C; section 7 thresholds ratified; D1, D4, D5 frozen |
| G1 (backend foundation) | RT-1 to RT-5, SEC-1, SEC-2, STR-1, STR-5, LC-3, WS-1, WS-2 pass headless on devices |
| G2 (application rebuild) | UI runs entirely on the bridge; Capacitor removed; regression map complete |
| G3 (validation) | All of section 7 passes on Tier A/B/C, including the 4 h soak |
| G4 (release) | REL-1 to REL-4, SEC-3, SEC-5 pass; signed artifacts; install and upgrade docs published |

## 9. Risks and open questions

| Risk | Mitigation |
| --- | --- |
| Distribution policy blocks Play | D1 sideload-first; S9 decides before architecture freezes |
| OEM background killing | S7, LC-2, per-OEM diagnostics guidance |
| Upstream API churn (v1 to v2) | D5 pin, contract suite, nightly drift job |
| GPL and package licensing | docs/licenses, source offer, SEC-5 |
| Kernel and seccomp variance across devices | D2 minimum SDK, S1 matrix, RT-4 |
| Heat and battery from an always-on server | D11 idle shutdown, LC-4, LC-6 |
| Prompt-injected or hostile-repo agent actions | Permission prompts always visible (SEC-4); no auto-approve default |

Open questions to answer from code or devices before G0: (1) Does LocalServerManager pass OPENCODE\_SERVER\_PASSWORD today? (2) Where does the agent's bash tool run today, host or guest? (3) Does the pinned version expose PTY routes? (4) Which physical devices are actually available for Tier A/B/C? (5) Is the target audience sideload users, Play users, or both?

## Sources

- OpenCode server docs: https://opencode.ai/docs/server/
- Existing project README and audit: https://github.com/spector-dev9/Test and https://github.com/spector-dev9/Test/blob/main/AUDIT-REPORT.md
- Termux and Android 10 (W^X, targetSdk 28): https://github.com/termux/termux-packages/wiki/Termux-and-Android-10
- Community OpenCode-on-Termux ports: https://github.com/nemoobc/opencode-termux, https://github.com/Hope2333/opencode-termux, https://github.com/HanSoBored/opencode-termux
- Play target-API and proot-from-nativeLibDir report: https://github.com/IQCoreTeam/AgentNet/issues/130
