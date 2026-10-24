# Threat model (v0.1)

Scope: the native Kotlin/Jetpack Compose app, the pinned OpenCode runtime
and guest environment, and their local interfaces. There is no React
WebView in the current app; the TypeScript bridge is retained as a
parity fixture, and the app-local bridge handler is test-only. Remote
provider traffic is in scope only for v1.1.

## Assets

| Asset | Why it matters |
| --- | --- |
| Provider credentials (`auth.json`, OAuth tokens) | Direct account compromise and billing |
| Git credentials and tokens | Repository takeover |
| Session transcripts and project files | Private code and prompts |
| Loopback server secret (`OPENCODE_SERVER_PASSWORD`) | Full control of shell/file routes |
| Keystore-wrapped secrets | Root of all local authentication |
| Runtime artifacts and manifest | Supply-chain integrity |
| Diagnostics/logs | Accidental disclosure of the above |

## Trust boundaries

1. **UI ↔ application services** — planned native service boundary; production app wiring is not implemented.
2. **Gateway ↔ OpenCode server** — HTTP/SSE on 127.0.0.1 with Basic auth.
3. **App ↔ other local apps** — same-device UIDs; loopback is not private.
4. **Guest (Alpine/PRoot) ↔ Android host** — shared filesystem and process tree.
5. **App-private storage ↔ shared storage** — import/export only.
6. **App ↔ upstream** — pinned artifacts, GitHub releases, providers.

## Actors

- Another app on the same device with INTERNET permission.
- A hostile repository opened by the user (hooks, configs, symlinks).
- A prompt-injected agent acting on hostile repo or web content.
- A compromised or malicious upstream artifact/provider.
- A local attacker with physical/backup access (limited scope).
- The user, making mistakes (auto-approve, pasted secrets).

## Threats and controls

| ID | Threat | Impact | Controls | Residual / verification |
| --- | --- | --- | --- | --- |
| T1 | Another local app calls loopback routes | Shell/file control | Random 256-bit per-launch password, Basic auth as `opencode:<secret>`, ephemeral port and 127.0.0.1 bind are required | Core credential generation and Basic verification exist and pass host tests. An app Android Keystore AES-GCM `SecretStore` adapter is implemented with host-tested encrypted-file mechanics, but actual Keystore device validation, listener/gateway wiring and SEC-1 second-UID verification remain pending; production wiring is G0-gated. On OpenCode 1.19.3, omitting the password leaves routes open. |
| T2 | Hostile repository content (hooks, config, symlinks) | Code execution, data theft | Workspace path/symlink guards (WS-1); permission prompts and visible Git hook execution are required | Host workspace guards and tests exist. Git and approval flows are not implemented; WS-2 durability and target-device acceptance remain open. Repo-hook fixture testing is pending. |
| T3 | Prompt-injected agent actions | Unwanted writes/shell | Permission requests must be shown and answerable; agent file/shell activity must be visible; no silent approvals | These UI/service flows are not implemented. Model-dependent risk remains; add E2E prompt-injection fixtures after integration. |
| T4 | Credential exfiltration | Account/repository compromise | Keystore-backed secret storage, Git credentials outside URLs, provider credential mode 600, log redaction and backups disabled | Android Keystore AES-GCM storage adapter is implemented, with host tests covering ciphertext-only persistence, isolation, deletion and symlink escape rejection. Device Keystore validation, production credential provisioning/wiring, log redaction, Git credential handling and SEC-2 scans remain pending. The current app manifest disables backups. |
| T5 | Privileged command or service-boundary misuse | Actions without user intent | Native Compose; service ports and typed command contract; validate inputs and require explicit authorization at the eventual app-service boundary | No WebView or production bridge handler exists. TypeScript bridge parity and app bridge tests are fixtures only, not a security boundary. WebView/CSP controls are not applicable. SEC-3 lint now checks the merged release manifest for exported components, backups, permissions and global cleartext; the release APK/CI gate and loopback-only cleartext policy remain pending. |
| T6 | Path traversal / symlink escape | Read/write outside project | Canonical workspace-root checks, no symlink traversal, mtime+size+SHA-256 write preconditions, trash/restore; WS-1 100,000-case path run | Host implementation and tests exist, including symlink and 100,000-path rejection cases. A 1,000-interruption host SIGKILL atomic-write stress run passed; G1 device acceptance, target-filesystem durability and performance acceptance remain open. |
| T7 | Runtime tampering / supply chain | Arbitrary code execution | Exact manifest pins and SHA-256 verification; immutable installation; unknown majors refused; SBOM and osv-scanner (SEC-5) | OpenCode/Alpine installers now support HTTPS-only bounded streaming downloads with SHA-256 verification and atomic publication before the existing verified install path. Full runtime-content validation, PRoot/runtime package delivery, production process launch and reproducible builds remain pending. The plan downloads pinned runtime artifacts on first run; they are not shipped in the APK. |
| T8 | Secret leakage via logs/backups | Credential disclosure | SEC-2 scan of APK/logcat/app logs/crash dumps/process arguments; provider credentials mode 600; `allowBackup=false`; diagnostics redaction | `allowBackup=false` is present in the app manifest. Secret scanning, production redaction and provider-credential handling are not implemented or verified. |
| T9 | Guest escape / host compromise via PRoot | Host compromise | Run PRoot as app UID without root; expose only approved paths; permission prompts | Accepted residual risk: PRoot is ptrace-based and is not a security boundary. Production bind restrictions and permission UI are not implemented. |
| T10 | Downgrade or update abuse | Reintroduce fixed bugs | Refuse downgrade; verify signing; pin runtime artifacts | Update/rollback policy, signing verification and CI signing remain pending. |
| T11 | Resource exhaustion / thermal abuse | Battery, heat, DoS | Idle shutdown, busy-only wake lock, crash-loop breaker (D11); LC-4/LC-6 thresholds | Device tests pending. |
| T12 | Provider MITM (v1.1 remote profile) | Credential/session theft | TLS 1.2+, certificate validation, fail closed, no cleartext (SEC-6) | Not in v1 scope. |
| T13 | Developer-verification gate blocks installs | Users cannot install/update | Register application ID/signing identity; document ADB/advanced flow; monitor 2026 regional and 2027 global rollout | External policy change; see ADR 0001. |

## Verified baseline (2026-10-23)

- Legacy `LocalServerManager` started the server with **no password**;
  every route returned 200 to a local caller (F5 confirmed).
- The pinned 1.19.3 server **does** enforce Basic auth with
  `OPENCODE_SERVER_PASSWORD`, including `/global/health` and `/event`.
  The accepted username is `opencode`; Bearer and empty-user forms are
  rejected.
- PTY routes are documented in the pinned `/doc`; the new app does not
  yet use them. The plan still requires a native PTY rather than relying
  on OpenCode's agent-mediated shell routes.

## Assumptions

- The device is not rooted and the app sandbox/SELinux is intact.
- App-private storage is not readable by other apps.
- The user does not approve permissions blindly.
- Upstream OpenCode and provider behavior is untrusted but pinned.

## Out of scope

- Rooted devices, custom ROMs, physical attackers with unlocked
  bootloaders.
- Provider-side compromise.
- Supply-chain attacks on the user's network beyond TLS.

## Open questions

1. Does OpenCode support a unix-socket listener? If yes, assess it
   against authenticated loopback before production listener wiring.
2. Should the sideload build use MANAGE_EXTERNAL_STORAGE, and how is
   that surfaced to the user? (Play variant must stay SAF-only.)
3. Backup policy for the debug build vs release build.
4. How are git credentials scoped per host and revoked?
