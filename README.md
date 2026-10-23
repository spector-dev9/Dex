# OpenCode Mobile

Clean-room rebuild of an on-device Android client for OpenCode.

Status: **Phase 2 — runtime feasibility.** Host-side spikes and the
transport-agnostic bridge foundation are in place. Architecture is
provisional until gate G0; Android/device work still needs CI or another
machine.

- Technical specification: [docs/spec.md](docs/spec.md)
- Development environment audit: [docs/audit/environment.md](docs/audit/environment.md)
- Legacy application review (research only): [docs/audit/legacy-review.md](docs/audit/legacy-review.md)
- Phase 0 plan and proposed layout: [docs/plan/phase-0.md](docs/plan/phase-0.md)
- Threat model: [docs/threat-model.md](docs/threat-model.md)
- Spike status and evidence: [tests/spikes/README.md](tests/spikes/README.md)
- CI: [.github/workflows/ci.yml](.github/workflows/ci.yml)

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
  reconnect delivers a fresh `server.connected`; directory-scoped
  `/event?directory=…` is accepted.
- The SSE parser, resync algorithm and gateway client (Basic auth,
  directory scoping, backoff, 60 s idle watchdog) pass 27 tests,
  including 500 randomized drop scenarios (simulation, not a live
  prompt stream).
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

1. Run the live STR-5 fault-injection test during a streaming prompt
   (needs provider credentials and an app/UI session).
2. Complete S9 (distribution/licensing) and freeze D1/D4/D5 at G0.
3. Stand up Android CI (emulator API 31/33/36 plus a 16 KB-page device)
   for S1/S2/S7/S8.
