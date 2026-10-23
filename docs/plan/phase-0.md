# Phase 0 plan — repository setup, audit, feasibility

Status: **Phase 0 complete. Phase 2 in progress.**

## Done

- New empty Git repository at `~/opencode-mobile` (branch `main`).
- Legacy clone preserved separately as
  `~/opencode-mobile-legacy-reference` (reference only, pristine at
  `3a75b62`).
- Technical spec copied to `docs/spec.md`.
- Environment audit written.
- Legacy review written from README, audit report and selected code.
- Layout approved by the owner ("oc.md is approved, work as it says").
- Host-side Phase 2 spikes run with the pinned artifact:
  - S1 partial: binary runs, hash and 64 KB alignment verified, health
    in 4.6–5.6 s on the host.
  - S4 partial: heartbeat ≈10 s, reconnect yields a fresh
    `server.connected`; directory-scoped `/event` accepted; SSE parser
    and resync algorithm implemented and exercised by a 500-trial
    drop simulation.
  - S5 pass (host): 36/36 planned endpoints, PTY documented, Basic auth
    verified with `OPENCODE_SERVER_PASSWORD`.
- `contract/` now holds the pinned `/doc` snapshot, the generated
  endpoint inventory and executable contract tests (11 passing).
- `ui/src/bridge/` holds the transport-agnostic protocol, SSE parser,
  resync algorithm and gateway client (auth, backoff, resync wiring),
  with 16 tests (27 total with contract tests).
- Threat model draft written; CI workflow for typecheck/tests/inventory
  reproducibility added.
- S9: D1 accepted (2026-10-23) — GitHub Releases + Obtainium, a
  self-hosted F-Droid repo after reproducible builds, bundled runtime,
  Play deferred, app code Apache-2.0. Compliance checklist is in
  `docs/licenses/`; release items remain open.

## Remaining skeleton (Phase 1, after G0 decisions)

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
   └─ *.test.ts              27 tests total with contract suite
app/                         Kotlin app shell, Gradle KTS, WebView only
core/                        interface-only modules (runtime, supervisor,
                             gateway, workspace, terminal, security)
docs/adr/                    D1–D13 records at G0
docs/threat-model.md
docs/runtime-matrix.md
.github/workflows/           ci, android-debug, drift
```

Interfaces only at first. Implementations land only after G0 freezes
D1 (distribution), D4 (runtime profile) and D5 (version pin).

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
| S2 | blocked (device + Profile B) | — |
| S3 | blocked (provider + app) | — |
| S4 | partial (host + simulation) | `tests/spikes/results/S4-sse.md` |
| S5 | pass (host) | `tests/spikes/results/S5-endpoints-auth.md` |
| S6 | blocked (2.0 artifact) | — |
| S7 | blocked (device) | — |
| S8 | blocked (device + keyboards) | — |
| S9 | accepted (D1); licensing checklist in progress | `docs/adr/0001-distribution.md`, `docs/licenses/README.md` |

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

## Next experiments

1. **S4 live STR-5:** run the resync algorithm against 500 real drops
   during a streaming prompt (needs provider credentials and app/UI
   session); then measure event-to-paint.
2. **S6:** run the contract suite against a 2.0.x artifact when one is
   available; record pass/fail per used endpoint.
3. **S1 app-sandbox:** CI emulator (API 31/33/36) + a 16 KB-page
   device; verify start, health and `execute_no_trans` denials.
4. **S9:** complete the `docs/licenses/` checklist (SBOM, notices,
   source offers, trademark, developer verification).
5. **Bridge hardening:** abort semantics against the live server and
   WebView transport wiring before G0 interface freeze.
