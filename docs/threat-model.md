# Threat model (v0.1)

Scope: the on-device app (Kotlin native layer + React WebView), the
pinned OpenCode runtime and guest environment, and their local
interfaces. Remote-provider traffic is in scope only for v1.1.

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

1. **WebView ↔ Kotlin bridge** — typed commands/events over WebMessagePort.
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
| T1 | Local app calls loopback routes | Shell/file control | D6: random 256-bit per-launch secret, Basic auth, Keystore; free port; 127.0.0.1 bind; gateway is the only holder | Core credential generation and Basic verification are implemented behind `SecretStore`; production Keystore storage, listener integration, and SEC-1 second-UID testing remain open. On 1.19.3, no password leaves all routes open; setting the password enforces Basic `opencode:<secret>`. |
| T2 | Hostile repo content (hooks, config, symlinks) | Code execution, data theft | Explicit permission prompts (SEC-4); no auto-approve default; workspace path policy (WS-1); git hooks visible; HTTPS-only git (D9) | Repo-specific hook execution remains user-visible but powerful. Needs a test fixture repo. |
| T3 | Prompt-injected agent actions | Unwanted writes/shell | Every permission request shown and answerable (SEC-4); transcript shows agent file/shell calls; no silent background approvals | Model-dependent. Add E2E prompt-injection fixtures. |
| T4 | Credential exfiltration | Account/repo compromise | Keystore-wrapped secrets (D7); GIT_ASKPASS helper, no tokens in URLs (D9); provider credentials mode 600; log scrubber (SEC-2); backups disabled | Verified only by design so far; SEC-2 scan pending. |
| T5 | WebView/bridge injection | Privileged actions without user intent | WebViewAssetLoader, no remote content in the app origin; typed bridge with allowlisted commands; no `addJavascriptInterface`; CSP | Needs SEC-3 manifest/CSP lint and a bridge fuzz test. |
| T6 | Path traversal / symlink escape | Read/write outside project | D8 canonical-path checks, no symlink escape, mtime+size+hash preconditions, in-app trash; WS-1 fuzz (100k paths) | Implementation pending; WS-1 is release-blocking. |
| T7 | Runtime tampering / supply chain | Arbitrary code execution | D5 exact pin + sha-256 + manifest; unknown majors refused; runtime shipped in APK; SBOM/osv-scanner (SEC-5); no first-launch download | Host spike verified hash and 64 KB alignment. Reproducible build pending (REL-1). |
| T8 | Secret leakage via logs/backups | Credential disclosure | SEC-2: scan APK/logcat/crash dumps/process args; mode 600; `allowBackup=false`; redaction in diagnostics | Pending implementation and scan. |
| T9 | Guest escape / host compromise via PRoot | Host compromise | PRoot is ptrace-based, not a security boundary; run as app UID; no root; guest only sees app-private dirs; permission prompts | Accepted residual risk; document that PRoot is not a sandbox. |
| T10 | Downgrade or update abuse | Reintroduce fixed bugs | REL-2 downgrade refused; signing verification (REL-3); pinned runtime; CI signing | Pending. |
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
- PTY routes are documented in the pinned `/doc`; they are still not
  used by the new app (D10).

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

1. Does OpenCode support a unix-socket listener? If yes, prefer it over
   loopback (D7 reversal trigger).
2. Should the sideload build use MANAGE_EXTERNAL_STORAGE, and how is
   that surfaced to the user? (Play variant must stay SAF-only.)
3. Backup policy for the debug build vs release build.
4. How are git credentials scoped per host and revoked?
