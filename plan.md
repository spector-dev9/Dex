# AndCode Rebuild: Audit and Technical Specification

**Version 0.1 (draft) · 23 Oct 2026**

> **Superseded for G0 by `docs/spec.md` and `docs/plan/phase-0.md`.**
> The v1 target is the owner's **Infinix X6870 only** (Android 16/API 36,
> arm64-v8a, 4 KB pages, 5.7 GB RAM, Gboard). Tier A/B/C, 16 KB-page devices,
> Samsung Keyboard, multi-device matrices and 19/20 repeat requirements in
> this legacy blueprint are historical and do not block G0.

Base repository: yuga-hashimoto/and-code (MIT licence). Reference only: spector-dev9/Test. This replaces the Capacitor-based plan in the original blueprint.

## 1. Decisions at a glance

Every number in this document is a provisional budget. Phase 0 measures a real baseline on physical devices, then the numbers are frozen as regression gates (§10).

| ID | Decision | Why | Revisit if |
| --- | --- | --- | --- |
| D1 | Fork and refactor and-code. Do not rewrite from scratch. | Per its docs it already has a PRoot runtime, pinned manifest, atomic update and rollback, foreground-service supervision, a shared REST/SSE client and diagnostics. A rewrite re-spends effort on solved problems. | Phase 0 finds the runtime logic cannot be separated from the UI without a rewrite. |
| D2 | UI is native Jetpack Compose. Capacitor and TypeScript are dropped. | and-code is Kotlin and Compose. A WebView layer adds a second UI stack and a bridge to maintain. | None expected. |
| D3 | Runtime is Alpine minirootfs under PRoot. arm64-v8a is primary; x86\_64 is for emulator CI only. | Already runs the official OpenCode musl binary without root. | Spike S1 fails on physical devices. |
| D4 | Backend is the official OpenCode release binary, pinned in a manifest. No Node.js in the base image; Node and npm are optional on-demand packages for user projects. | The agent ships as a standalone binary. The Node need in your blueprint applies to user projects, not to the agent. | The pinned release stops shipping a musl binary. |
| D5 | Transport is HTTP plus SSE against OpenCode’s OpenAPI 3.1 spec. Send with prompt\_async, render from /event. No WebSocket bridge. | Documented upstream and already used by and-code. | Soak spike S2 fails. |
| D6 | The local server is authenticated even on loopback: ephemeral port, random per-launch password. | Any app on an Android device can connect to 127.0.0.1. Loopback binding alone is not an access boundary. | None. |
| D7 | Create, edit, rename, move and delete live in a native Kotlin WorkspaceService working on the host filesystem. | OpenCode’s documented file API is read, list and search only. The workspace is an ordinary Android directory, so Kotlin can write it directly with atomic rename. | OpenCode adds a documented write API with equivalent safety. |
| D8 | The terminal is a real PTY: a native forkpty helper starts a PRoot shell, shown in a terminal-emulator view. | OpenCode’s shell endpoint is agent-mediated, not interactive. and-code today offers bounded commands only. | Spike S3 fails. |
| D9 | Alpine git is the single source of truth for Git. JGit is adopted only if spike S4 shows PRoot git status above 1.5 s p95 on a 5,000-file repository. | Two Git implementations can disagree on hooks, config and ignore rules. | S4 result. |
| D10 | Distribute through GitHub Releases and F-Droid. Google Play is not a v1 target. | Downloading executables and all-files access are policy-sensitive on Play, and and-code already carries F-Droid metadata. Check current Play policy before relying on this. | Policy review. |

## 2. What this is based on

**Reviewed:** your blueprint; the and-code README, docs/LOCAL\_RUNTIME.md, docs/device-matrix.md and AGENTS.md; the official OpenCode server docs (opencode.ai/docs/server), which list the HTTP API.

**Not reviewed:** the app/ source tree (GitHub blocked automated access to it), issues and PRs, CI workflow files, docs/RELEASE.md and local-runtime-manifest.json. Statements about and-code below are what its own docs claim, not what I confirmed in code. Each one is tracked in §11 for verification. I also did not read spector-dev9/Test; its limitations come only from your blueprint’s problem table.

## 3. Audit of and-code

### 3.1 Your problem table against and-code today

Rows 3 and 4 of your table are merged.

| # | Your problem | and-code today (per its docs) | Spec response |
| --- | --- | --- | --- |
| 1 | Polling-only updates | Claims SSE live responses, tool output and approvals. Local and remote share one REST/SSE client. | Keep. Add reconnect reconciliation and a soak test (ST-1 to ST-6). |
| 2 | Fixed loopback server | Local server fixed at 127.0.0.1:4097. Remote profiles use HTTP basic auth over LAN or Tailscale, with per-connection opt-in for plain HTTP. Local-server authentication is not mentioned. | Ephemeral port and per-launch password (D6, SC-1 to SC-3). HTTPS by default for remote. |
| 3 | Incomplete file operations; restricted creation and editing | Browse, search, preview, syntax highlighting. No create, edit, rename, move or delete is listed. | New WorkspaceService (D7, WS-1 to WS-6). |
| 4 | No interactive PTY | Bounded shell commands only. An Antigravity launcher uses a PTY, so some PTY code may exist. | PtyService (D8, TM-1 to TM-5). |
| 5 | Large and binary files | Not documented. | Ranged reads, binary detection, bounded memory (WS-4). |
| 6 | Non-live context information | Not documented. | Derive from the latest assistant message’s token counts and the model’s context limit; label as estimated (§6.2). |
| 7 | Process lifecycle fragility | Foreground service; state machine (not installed, installing, starting, running, stopped, corrupt, unsupported ABI); 30 s health check with auto-recovery; process-tree memory diagnostics; full uninstall. | Keep. Add restart backoff, crash-loop stop and OEM-kill tests (RT-4 to RT-8). |
| 8 | Debug-only build workflow | Debug and release Gradle flavours, detekt, spotless and lint. The local release output is an unsigned APK; signing lives in docs I did not read. Fastlane and F-Droid metadata exist. | Verify the signing pipeline. Add reproducible-build and upgrade tests (RL-1 to RL-6). |
| 9 | Hardcoded networking and timeouts | Not documented. | Per-operation timeout policy (§6.5). |
| 10 | Unverified mobile behaviour | Device matrix has only emulator rows complete (API 26 and 34 on x86\_64 CI, API 36 on ARM64 locally). Physical-device rows are Pending and the battery table is empty. | Phase 0 physical baseline (§10). |

### 3.2 Risks the blueprint did not list

- **R1. No JVM tooling on-device.** Per and-code’s docs, PRoot’s ptrace-based syscall emulation cannot provide the executable memory pages the JVM needs, so Gradle, Kotlin and Java builds do not run inside the runtime at any project size. Treat this as a documented non-goal and offload builds to a remote OpenCode host.
- **R2. Shared storage breaks some Git behaviour.** /sdcard sits on FUSE, which keeps neither execute bits nor symlinks. and-code itself recommends /workspace for lasting work.
- **R3. All-files access widens the agent’s reach.** Granting it binds /sdcard and /storage into the guest. PRoot is not a sandbox, so the agent can read and modify anything those paths expose.
- **R4. Version drift in docs.** The README pins OpenCode 1.18.5 while docs/LOCAL\_RUNTIME.md refers to 1.18.3. The manifest must be the only source of versions, with a CI check that docs match it.
- **R5. Scope breadth.** Four agents, a second rootfs, voice, wake word, assistant role, widget, scheduling and an ADB bridge all enlarge the attack surface and the test matrix (§3.3).
- **R6. Credentials in QR codes.** The opencode://connect deep link can carry a plaintext password. Default to not embedding it.
- **R7. Upstream churn.** OpenCode’s docs now point to github.com/anomalyco/opencode and announce a v2. The API can change underneath you. Pin the OpenCode version, vendor its openapi.json and run contract tests on every bump.
- **R8. Android process limits (general platform knowledge, not from the repo).** Android 12 and later can kill child processes aggressively (the phantom-process limit), Android 14 requires a declared foreground-service type, and OEM battery managers kill background services. These are the likeliest causes of an unreliable local server, which is why the physical-device baseline comes first.

### 3.3 Scope for v1

Ship OpenCode only, behind an AgentAdapter interface so other agents can return later. Move voice, wake word, assistant role, widget, schedules, the ADB bridge, Claude Code, Codex, Antigravity and the Debian rootfs out of the core build into optional Gradle modules instead of deleting them, so upstream fixes can still be merged.

## 4. Runtime decision

| Option | Verdict | Reasoning |
| --- | --- | --- |
| Alpine + PRoot | **Chosen** | Per and-code’s emulator tests it runs the official OpenCode musl binary, with no root and a small base image. Costs: ptrace overhead on every syscall, no JVM, not a security sandbox. |
| Depend on Termux | Rejected | Contradicts the no-separate-install goal. Termux packages assume a fixed install prefix, so reuse means rebuilding or relocating them. Termux’s package-resolution tooling can still help build the helper set (and-code does this). |
| Native Android (bionic) binaries | Rejected for OpenCode; kept for helpers | OpenCode’s official builds target Linux libc, not bionic. Android 10 and later blocks executing files from app-private storage, so only binaries packaged as native libraries run directly. That is why proot ships inside the APK. |
| Debian/glibc rootfs | Deferred | Needed only for glibc-only binaries (and-code uses it for Antigravity). It adds a second rootfs and download. |
| Full VM (QEMU or Android Virtualization Framework) | Rejected for v1 | Slow start and high memory without hardware virtualisation, which is not broadly available. Revisit if PRoot’s weak isolation or missing JVM support becomes the blocking constraint. |
| Hybrid | **Chosen shape** | PRoot for the agent and userland. Native Kotlin for supervision, file operations and PTY plumbing, where PRoot’s overhead buys nothing. |

### 4.1 Runtime specification

- **In the APK, per ABI:** proot, its loader and a small PTY helper, as native libraries (minSdk 26, matching and-code’s device matrix). Build them from pinned sources in CI so the binaries are reproducible. How and-code builds them today is unverified.
- **Downloaded on first run, pinned in the manifest** (HTTPS URL, size, SHA-256): Alpine minirootfs and the OpenCode musl binary. Then apk installs git, bash, curl, openssh-client, ca-certificates, ripgrep and libstdc++, as and-code does.
- **On demand only:** nodejs, npm and python3 through a Toolchains screen. Never in the base image.
- **Process model:** foreground service, then Supervisor, then proot, then `opencode serve --hostname 127.0.0.1 --port <ephemeral>` with `OPENCODE_SERVER_PASSWORD` set to a random per-launch secret. One process group. Stop means SIGTERM, a 3 s grace period, then SIGKILL on the whole tree.
- **Supervisor policy:** health probe on `/global/health` every 10 s while Ready. Restart backoff 1, 2, 4, 8 s up to 60 s. After 5 failures in 10 minutes, enter Degraded, stop restarting and show diagnostics.
- **Filesystem:** keep and-code’s `files/runtime/` layout (metadata, rollback journal, `environment/rootfs`, `workspace`, `logs`). The host-side workspace directory is the single source of project files, bind-mounted at `/workspace` in the guest.
- **Storage access:** the default is app-private `/workspace` only. External folders come through SAF import or the opt-in all-files bind, which needs an explainer and a runtime restart because PRoot fixes its binds at launch.
- **Documented non-goals:** Gradle, Kotlin and Java builds, Docker, nested emulators, iOS builds, long high-load jobs. Offer a remote profile instead.

## 5. Architecture and repository structure

### 5.1 Layers

```
UI (Compose features)
  │ talks only to service interfaces
Application services: RuntimeProvider · OpenCodeApi · WorkspaceService · PtyService · GitService
  │
Runtime manager + Supervisor ──► PRoot ──► Alpine ──► opencode serve (loopback, ephemeral port, basic auth)
  │                                                       ▲
Workspace on host FS (atomic writes) ── bind /workspace ──┘
Security and persistence: Keystore vault · NetworkPolicy · log redaction · DataStore
```

| Your layer | Module | Responsibility |
| --- | --- | --- |
| 1. User interface | feature/\* | Compose screens. Imports only core/model and service interfaces. |
| 2. Application services | interfaces in core/\* | Stable boundary. The UI never learns which distribution or runtime sits below. |
| 3. Runtime manager | core/runtime | Install, update, rollback, supervise, diagnose. |
| 4. OpenCode backend | runs in the guest | Agent, sessions, permissions, SSE. |
| 5. Workspace and terminal | core/workspace, core/terminal | Files, Git, PTY. |
| 6. Security and persistence | core/security | Vault, network policy, redaction, settings. |

### 5.2 Target repository structure

Directories marked (exists) are in and-code today. The rest is the target; I could not verify the current layout inside app/.

```
andcode/                    fork of yuga-hashimoto/and-code
├─ app/                     thin shell: Application, navigation, DI, flavours
├─ core/
│  ├─ model/                pure Kotlin: Session, Message, Part, Permission, FileEntry
│  ├─ runtime/              RuntimeProvider, Installer, Updater, Supervisor, Manifest
│  ├─ opencode-client/      OkHttp REST + SSE, types generated from the pinned openapi.json
│  ├─ workspace/            WorkspaceService, PathGuard, AtomicWriter, ConflictDetector, GitService
│  ├─ terminal/             PtyService, scrollback ring buffer, session registry
│  ├─ security/             KeystoreVault, NetworkPolicy, Redactor
│  └─ testing/              FakeOpenCodeServer, FakeRuntime, fixtures
├─ feature/                 chat, sessions, files, git, terminal, settings, diagnostics
├─ optional/                voice, schedules, adb-bridge, other-agents (excluded from core build)
├─ native/                  proot, loader, PTY helper (CMake, per ABI)
├─ runtime_manifest/        manifest JSON (single source of versions), schema, verifier
├─ contract/                pinned openapi.json per OpenCode version + contract tests
├─ benchmark/               (exists) Macrobenchmark and battery scripts
├─ runtime_tools/           (exists)
├─ config/detekt/           (exists)
├─ scripts/                 (exists)
├─ fastlane/  store/  pages/   (exist)
└─ docs/                    (exists) + adr/, audit/, threat-model.md, traceability.md, UPSTREAM.md
```

### 5.3 Rules and migration

- Dependencies point downward only. `feature/*` must not depend on `core/runtime`. A CI test enforces this (RL-6).
- Migrate by strangler pattern: (1) add contract tests and a traceability file around current behaviour; (2) extract core/model and core/opencode-client; (3) extract core/runtime; (4) add workspace, terminal and security; (5) retire the old paths. Every step keeps the debug build green and the device smoke test passing.
- Keep an `upstream` remote and record divergences in `UPSTREAM.md` so fixes can be cherry-picked.
