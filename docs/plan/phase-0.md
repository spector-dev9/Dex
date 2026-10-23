# Phase 0 plan — repository setup, audit, feasibility

Status: **Phase 0 complete. Phase 2 in progress.**

## Done

- New empty Git repository at `~/opencode-mobile` (branch `main`).
- Legacy clone preserved separately as
  `~/opencode-mobile-legacy-reference` (reference only, pristine at
  `3a75b62`).
- Technical spec copied to `docs/spec.md`; its Section 4.3 command list
  is the recorded typed expansion of the approved `oc.md` shorthand.
- Environment audit written.
- Legacy review written from README, audit report and selected code.
- AndCode reuse review written from source and tests (research only); no
  code copied.
- Runtime profile matrix written (`docs/runtime-matrix.md`); D4 remains
  open pending device measurements.
- Layout approved by the owner ("oc.md is approved, work as it says").
- Host-side Phase 2 spikes run with the pinned artifact:
  - S1 partial: binary runs, hash and 64 KB alignment verified, health
    in 4.6–5.6 s on the host.
  - S2 partial (host): Profile A and Profile B both start and complete a
    mock-provider round trip; device/git/npm/thermal comparisons pending.
  - S4 pass (host, mock + real provider): heartbeat ≈10 s, reconnect
    yields a fresh `server.connected`; directory-scoped `/event`
    accepted; SSE parser and resync algorithm passed a 500-trial drop
    simulation, live 500-drop runs against both a mock and a hosted
    model, and an authenticated 500-drop reconnect loop; final models
    matched the authoritative snapshot.
  - S3 partial (host guest): the agent's bash tool invoked the `SHELL`
    wrapper and executed inside PRoot Alpine (`pwd` = `/root`, exit 0);
    the app sandbox and native PTY comparison remain untested.
  - S5 pass (host): 36/36 planned endpoints and Basic auth verified with
    `OPENCODE_SERVER_PASSWORD`; PTY routes are documented but `/pty`
    fails because the bundled `bun-pty` library is glibc-linked.
- `contract/` now holds the pinned `/doc` snapshot, the generated
  endpoint inventory and executable contract tests (11 passing).
- `ui/src/bridge/` holds the transport-agnostic protocol, SSE parser,
  resync algorithm and gateway client (auth, backoff, resync wiring),
  with 19 tests (30 total with contract tests); text deltas are applied
  live and part updates remain authoritative.
- Threat model draft written; CI workflow for typecheck/tests/inventory
  reproducibility and a nightly contract-drift workflow added.
- Plan-to-evidence traceability recorded in `docs/traceability.md`;
  contract evidence is explicitly separated from Android implementation
  and physical-device acceptance.
- S9: D1 accepted (2026-10-23) — GitHub Releases + Obtainium, a
  self-hosted F-Droid repo after reproducible builds, bundled runtime,
  Play deferred, app code Apache-2.0. Compliance checklist is in
  `docs/licenses/`.
- S9 artifacts generated: 14 Alpine packages, 45 Bun-bundled
  libraries/polyfills, pinned PRoot/libtalloc/libandroid-shmem, a
  67-component main CycloneDX 1.5 SBOM, an 849-package OpenCode
  dependency SBOM, per-package Alpine copyright notices, notices,
  license texts and a source-offer draft. Bun 1.3.14, WebKit/JSC and
  tinycc sources are pinned and static JSC/tinycc linkage confirmed;
  PRoot dynamically links libtalloc. Open blockers: Bun LGPL object-file/source-offer review and
  verification of the dependency closure against the built binary.

## Target layout (contracts now; implementations after G0)

```
ui/
├─ index.html
├─ src/main.tsx
├─ src/App.tsx
└─ src/bridge/
   ├─ protocol.ts            envelopes, commands, events, errors (done)
   ├─ sse.ts                 SSE parser (done)
   ├─ resync.ts              STR-5 resync algorithm (done)
   ├─ client.ts              authenticated SSE client (done)
   └─ *.test.ts              30 tests total with contract suite
app/                         Kotlin app shell, Gradle KTS, WebView only
core/                        ten `*-api` interface modules (contracts compile;
                             runtime, supervisor, gateway, workspace, git,
                             terminal, security, diagnostics, bridge, common)
docs/adr/                    D1–D13 records at G0
docs/threat-model.md       done
docs/runtime-matrix.md      done; D4 decision at G0
.github/workflows/           ci, android-debug, drift
```

Interfaces only at first. Implementations land only after G0 freezes
D1 (distribution), D4 (runtime profile) and D5 (version pin).

## G1 interface scaffold (2026-10-23)

- [x] Ten `core/*-api` modules with Java 21/Kotlin 2.1.20 contracts.
- [x] Dependency rule: every service module depends only on `common-api`;
      `bridge-api` mirrors protocol v1 without service dependencies.
- [x] Architecture checker rejects Android imports, cross-module imports
      and unexpected project dependencies.
- [x] TypeScript/Kotlin parity check for version, errors, retryability,
      timeouts, event topics and envelope types.
- [x] `./gradlew -PskipApp=true coreCheck` passes locally; Gradle 8.13
      wrapper is checksum-pinned.
- [x] Compiled-class reflection parity for commands, errors, timeouts and
      event topics; `@RequiresG0` markers and `SupervisorConfig`.
- [x] Minimal Android app shell; debug/release assembly in CI.
- [ ] Bridge handler, fake service implementations and command payload
      DTOs (after G0).
- [ ] G1 acceptance (RT-1..RT-5, SEC-1/2, STR-1/5, LC-3, WS-1/2) on
      devices.

See `docs/architecture/g1-contracts.md`.

## Phase 0 exit criteria

- [x] Layout approved by the owner.
- [x] Build environment chosen: CI for Android; this host for
      Node/Python spikes.
- [x] S9 started: distribution/licensing decision path recorded as an
      open owner decision.
- [x] Runtime feasibility plan documented, including device-access gap.
- [x] Threat model draft (`docs/threat-model.md`).

## Spike status

| ID | Status | Evidence |
| --- | --- | --- |
| S1 | partial (host) | `tests/spikes/results/S1-host-smoke.md` |
| S2 | partial (host); device + git/npm + thermals pending | `tests/spikes/results/S2-host-profiles.md` |
| S3 | partial (host); app sandbox + PTY pending | `tests/spikes/results/S3-shell.md` |
| S4 | pass (host, mock + real provider); app/UI pending | `tests/spikes/results/S4-sse.md` |
| S5 | pass (host) | `tests/spikes/results/S5-endpoints-auth.md` |
| S6 | blocked (no 2.x release asset); 1.x drift job added, latest 1.19.4 clean | `tests/spikes/results/S6-2.x.md` |
| S7 | blocked (device) | — |
| S8 | blocked (device + keyboards) | — |
| S9 | accepted (D1); SBOM/inventory done, blockers open | `docs/adr/0001-distribution.md`, `docs/licenses/` |

## Corrections to the spec found during spikes

1. **F3 is outdated for 1.19.3.** The pinned `/doc` documents 9 PTY
   routes. D10 can remain (server-down terminal) but not for the reason
   "undocumented".
2. **Section 4.1 parameter names are `{sessionID}`/`{permissionID}`**,
   not `:id`. The inventory uses the real names.
3. **The pinned spec has 51 `/api/*` paths** absent from Section 4.1;
   add them to drift monitoring.
4. **Auth is HTTP Basic with user `opencode`**; health and SSE are
   protected too, so the supervisor and gateway must authenticate both.
5. **Without a password, all routes return 200** to any local process,
   confirming F5 at the highest severity.
6. **Bun 1.3.14 statically links JavaScriptCore (LGPL-2.0-only) and
   tinycc (LGPL-2.1-only).** The community artifact's MIT license does
   not cover these; S9 must resolve relink/source-offer duties.
7. **Section 4.3 is an explicit expansion, not a replacement.** The
   approved `oc.md` writes `session.*` and omits `terminal.signal`; the
   tracked contract enumerates 17 session proxies and adds
   `terminal.signal` for D10/TERM-2. No approved command is removed.
   This remains provisional until G0.

## Next experiments

1. **S3 app sandbox + native PTY:** run the guest wrapper inside the
   app and compare agent-shell/PTY `pwd`, `node -v` and `PATH`.
2. **S4 app/UI:** repeat the live 500-drop run through the app/UI
   bridge and measure event-to-paint.
3. **S6:** run the contract suite against a 2.0.x artifact when one is
   published; `v2.0.32`/`v2.1.3` tags exist but have no release assets
   or npm builds as of 2026-10-23.
4. **S1 app-sandbox:** CI emulator (API 31/33/36) + a 16 KB-page
   device; verify start, health and `execute_no_trans` denials.
5. **S9:** resolve Bun/libtalloc LGPL compliance, verify the OpenCode
   closure against the binary, review Alpine packages without separate
   copyright files, and complete the remaining checklist (trademark,
   developer verification).
6. **Bridge hardening:** abort semantics against the live server and
   WebView transport wiring before G0 interface freeze.
7. **G1 implementation:** add the command handler, JSON payload DTOs and
   fake-backed contract tests after G0.
