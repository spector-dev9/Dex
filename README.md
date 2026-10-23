# OpenCode Mobile

Clean-room rebuild of an on-device Android client for OpenCode.

Status: **Phase 2 — runtime feasibility.** Repository setup and the
first host-side spikes are complete. Architecture is provisional until
gate G0.

- Technical specification: [docs/spec.md](docs/spec.md)
- Development environment audit: [docs/audit/environment.md](docs/audit/environment.md)
- Legacy application review (research only): [docs/audit/legacy-review.md](docs/audit/legacy-review.md)
- Phase 0 plan and proposed layout: [docs/plan/phase-0.md](docs/plan/phase-0.md)
- Spike status and evidence: [tests/spikes/README.md](tests/spikes/README.md)

## Verified so far (host-side, 2026-10-23)

- Pinned OpenCode 1.19.3 runs through the bundled musl loader on this
  aarch64 device; artifact sha-256 and 64 KB ELF alignment verified.
- `/global/health` is 200 within 4.6–5.6 s on the host (not the app
  sandbox).
- `OPENCODE_SERVER_PASSWORD` enforces HTTP Basic auth (`opencode:<secret>`)
  on every route, including health and SSE.
- Without a password every route is open to any local process —
  confirming the spec's highest-severity finding (D7).
- `/event` sends `server.connected` then heartbeats every ~10 s; a
  reconnect delivers a fresh `server.connected`.
- The pinned `/doc` lists all 36 planned endpoints; PTY routes are
  documented there (correcting spec F3), and 51 `/api/*` paths exist
  that Section 4.1 does not mention.

## Intended architecture (provisional)

- Native Android app in Kotlin: runtime manager, server supervisor,
  authenticated loopback gateway with SSE, workspace and Git services,
  native PTY, security and diagnostics.
- React + TypeScript UI in a WebView, connected through a typed bridge.
- Pinned OpenCode runtime shipped with the app; no first-launch download.
- Sideload-first distribution pending the licensing/distribution spike (S9).

## Clean-room rules

The old `spector-dev9/Test` repository is reference material only. Its
source tree, build files, Capacitor configuration and native
implementation are not copied. Individual pure-logic modules or UI
components may be ported only after a recorded dependency and test
review (spec Section 1.3).

## Environment constraint

This workspace (Termux on Android) has Node, Python, clang, make and
proot, but no JDK, Gradle, Android SDK or adb. Kotlin/Android builds and
device tests therefore run in CI or on another machine. See the
environment audit for details.

## Next step

Approve the proposed layout in `docs/plan/phase-0.md`, then run the
Phase 2 spike harnesses (runtime profile A/B, SSE contract, endpoint
inventory) before scaffolding the full application.
