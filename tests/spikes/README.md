# Phase 2 spikes (Section 6)

Device-dependent spikes cannot run in this Termux workspace (no JDK,
Gradle, Android SDK or adb). Host-side experiments are labeled
"partial" and do not replace app-sandbox or Tier A/B/C evidence.

| ID | Question | Pass rule | Status |
| --- | --- | --- | --- |
| S1 | Profiles A and B on Tier A/B/C | 100% of Tier A/B start; if A fails any, default to B | **partial** — pinned artifact runs on host; app sandbox untested → [result](results/S1-host-smoke.md) |
| S2 | A vs B performance | B rejected as default if >2.5x slower than A | **partial (host)** — both profiles start and complete a mock round trip; device/git/npm pending → [result](results/S2-host-profiles.md) |
| S3 | Agent bash honors SHELL into guest | pwd/node -v/PATH identical; else default B | **partial (host guest)** — agent bash ran in PRoot Alpine (`/root`); app sandbox and native PTY identity untested → [result](results/S3-shell.md) |
| S4 | SSE semantics | Written resync algorithm; STR-5 passes | **pass (host, mock + real provider)** — heartbeat ≈10 s, reconnect new `server.connected`, directory scoping accepted, parser + 500-trial simulation pass, live 500-drop runs against a mock and a hosted model plus an authenticated 500-drop loop match the snapshot (p95 reconnect 59 ms); app/UI untested → [result](results/S4-sse.md) |
| S5 | Endpoint inventory, PTY, auth | Inventory committed to contract/ | **pass (host)** — 36/36 endpoints, PTY documented, Basic auth verified → [result](results/S5-endpoints-auth.md) |
| S6 | Contract suite on 2.0.x | Pin 2.x only if every used endpoint passes | blocked — 2.x tags exist but no release assets/npm builds → [result](results/S6-2.x.md) |
| S7 | Background survival, OEM savers | LC-2 met or per-OEM guidance | blocked (needs device) |
| S8 | xterm.js vs native view | TERM-5 on Gboard + Samsung Keyboard | blocked (needs device) |
| S9 | Licensing and distribution | Written D1 decision + checklist | pending (owner decision) |

## Open questions from Section 9

1. `OPENCODE_SERVER_PASSWORD` today? **No** in the legacy app (code read).
   The server itself supports it — verified in S5.
2. Agent bash location? **Host Android** in the legacy app
   (`SHELL=/system/bin/sh`). S3 still needs an app-sandbox and native-PTY test.
3. PTY routes? **Documented in the pinned 1.19.3 `/doc`** (9 routes).
4. Physical devices? Owner input needed.
5. Audience (sideload vs Play)? Owner decision at S9.

## Reproduce

```bash
python3 scripts/fetch-runtime.py
python3 scripts/verify-runtime.py
python3 tests/spikes/scripts/probe_server.py --label no-auth --sse-seconds 25
python3 tests/spikes/scripts/probe_server.py --label with-auth --password test-secret
python3 tests/spikes/scripts/probe_server.py --label s4-scoped --sse-seconds 12
python3 scripts/build-inventory.py
npm install
npm run typecheck
npm test
```

Raw evidence lives in `results/raw/`. Do not commit downloaded runtime
binaries; the manifest pins them by URL and sha-256.
