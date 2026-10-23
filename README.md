# OpenCode Mobile

Clean-room rebuild of an on-device Android client for OpenCode.

Status: **Phase 2 — runtime feasibility.** Host-side spikes, the
transport-agnostic bridge foundation, ten `core/*-api` JVM contracts and a
minimal Android app shell are in place. Architecture remains provisional
until gate G0; service implementations and device work still need G0 and
CI or another machine.

- Technical specification: [docs/spec.md](docs/spec.md)
- Development environment audit: [docs/audit/environment.md](docs/audit/environment.md)
- Legacy application review (research only): [docs/audit/legacy-review.md](docs/audit/legacy-review.md)
- Phase 0 plan and proposed layout: [docs/plan/phase-0.md](docs/plan/phase-0.md)
- G1 core contracts: [docs/architecture/g1-contracts.md](docs/architecture/g1-contracts.md)
- Threat model: [docs/threat-model.md](docs/threat-model.md)
- Distribution ADR (accepted): [docs/adr/0001-distribution.md](docs/adr/0001-distribution.md)
- Licensing checklist: [docs/licenses/README.md](docs/licenses/README.md)
- Spike status and evidence: [tests/spikes/README.md](tests/spikes/README.md)
- Acceptance evidence: [docs/evidence/RESULTS.md](docs/evidence/RESULTS.md)
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
  directory scoping, backoff, 60 s idle watchdog) pass 30 tests,
  including 500 randomized drop scenarios, live 500-drop mock-provider
  and hosted-model runs (p95 reconnect 59 ms; final transcript
  identical), and an authenticated 500-drop reconnect loop.
- The live OpenCode event shape uses `message.part.delta` for token
  text and a final `message.part.updated` with the full part; the bridge
  now handles both.
- The agent's bash tool honors a `SHELL` wrapper and entered the PRoot
  Alpine guest (`pwd` = `/root`, exit 0); app-sandbox/native-PTY
  identity remains untested.
- Both Profile A and Profile B start and complete a mock round trip on
  the host. Profile B's PRoot server is viable; device/git/npm/thermal
  comparisons remain before choosing a default.
- The pinned `/doc` lists all 36 planned endpoints; PTY routes are
  documented there (correcting spec F3), and 51 `/api/*` paths exist
  that Section 4.1 does not mention. Server `/pty` creation fails on
  Android because its bundled `bun-pty` library is glibc-linked, so the
  native PTY in D10 is a hard requirement.
- A nightly contract-drift workflow compares the latest upstream `/doc`
  with the pinned contract; a local check against 1.19.4 found no
  breaking drift.
- G1 contracts now cover ten `core/*-api` modules, including a Kotlin bridge
  mirror of protocol v1 with 47 commands. Dependency and Android-import
  checks pass; compiled-class reflection verifies commands, errors,
  timeouts and event topics; `./gradlew coreCheck` compiles the contracts on
  JDK 21. A minimal Android app shell is present; CI assembles debug and
  release APKs and uploads them as run artifacts.

## S9 progress

- D1 accepted: GitHub Releases + Obtainium first, self-hosted F-Droid
  after reproducible builds, Play deferred; app code Apache-2.0.
- Generated a 14-package Alpine license inventory with per-package
  copyright notices, a 67-component main CycloneDX 1.5 SBOM, an
  849-package OpenCode dependency SBOM, notices, license texts and a
  source-offer draft under [docs/licenses](docs/licenses/README.md).
- Open blockers: Bun statically links LGPL JavaScriptCore/tinycc;
  libtalloc is dynamically linked under LGPL-3.0-or-later; verify the
  dependency closure against the built binary.

## Intended architecture (provisional)

- Native Android app in Kotlin: runtime manager, server supervisor,
  authenticated loopback gateway with SSE, workspace and Git services,
  native PTY, security and diagnostics.
- React + TypeScript UI in a WebView, connected through a typed bridge.
- Pinned OpenCode runtime shipped with the app; no first-launch download.
- GitHub Releases + Obtainium first; Play deferred (D1 accepted, ADR 0001).
- App clean-room code is Apache-2.0; third-party notices are tracked in
  [docs/licenses](docs/licenses/README.md).

## Clean-room rules

The old `spector-dev9/Test` repository is reference material only. Its
source tree, build files, Capacitor configuration and native
implementation are not copied. Individual pure-logic modules or UI
components may be ported only after a recorded dependency and test
review (spec Section 1.3).

## Environment constraint

This workspace (Termux on Android) has Node, Python, clang, make, proot,
OpenJDK 21 and the Gradle 8.13 wrapper, so the Kotlin/JVM core contracts
build locally. Android SDK/platform pieces and aarch64 build tools are
partial; local APK assembly is not supported. Android debug/release builds
and device tests therefore run in CI or on another machine. See the
environment audit for details.

## Next step

1. Run the S3 guest wrapper and native PTY inside the app sandbox, and
   compare agent-shell/PTY `pwd`, `node -v` and `PATH`.
2. Resolve the S9 blockers (Bun/libtalloc LGPL, Alpine notices,
   trademark) and complete the licensing checklist, then freeze D1/D4/D5
   at G0.
3. Validate the Android CI build job, then add emulator/device jobs
   (API 31/33/36 plus a 16 KB-page device) for S1/S2/S7/S8.
4. After G0, implement the bridge handler and fake-backed service contract
   tests before wiring the app module.

## License

The clean-room application code is licensed under Apache-2.0; see
[LICENSE](LICENSE). Bundled third-party components keep their own
licenses (OpenCode/musl MIT, Bun MIT plus bundled LGPL components,
PRoot GPL-2.0-or-later, Alpine packages mixed);
see [docs/licenses](docs/licenses/README.md) for notices and source
offers.
