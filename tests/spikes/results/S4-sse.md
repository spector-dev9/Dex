# S4 — SSE semantics (partial: host + simulation)

Date: 2026-10-23. Server: pinned 1.19.3 on 127.0.0.1, no password.
Capture: raw socket, 25 s, then reconnect after 1 s for 6 s, then a
12 s capture on `/event?directory=<workspace>`.

## Observed

| Property | Result |
| --- | --- |
| HTTP status | 200, `Content-Type: text/event-stream` |
| Transfer | chunked; `Cache-Control: no-cache, no-transform`; `x-accel-buffering: no` |
| First event | `server.connected` at 0.012 s |
| Heartbeat | `server.heartbeat` at 10.022 s and 20.056 s (≈10.03 s cadence) |
| Idle close | none within 25 s |
| Reconnect | new `server.connected` with a different `evt_…` id |
| Payload size | 89 B per event (511 B total for three events) |
| Directory scoping | `/event?directory=<workspace>` → 200, `server.connected` at 0.008 s, heartbeat at 10.010 s, no close |

Raw evidence: `results/raw/sse-no-auth-event.sse`,
`results/raw/sse-no-auth-event-reconnect.sse`,
`results/raw/s4-scoped-event-scoped.sse`,
`results/raw/s4-scoped-summary.json`.

## Implications

- A 60 s idle watchdog is safe against a ~10 s heartbeat.
- Reconnect must treat each `server.connected` as a resync trigger; it
  is not a replay and does not carry a cursor.
- The gateway must send Basic auth on the SSE request when a password
  is configured (verified in S5).
- Directory scoping is accepted by the server, but no project events
  appear without an active session; it cannot be distinguished from the
  unscoped stream in this capture.

## Resync algorithm and simulation

`ui/src/bridge/resync.ts` implements the STR-5 algorithm:

1. On `server.connected`, buffer live events.
2. Fetch the authoritative transcript snapshot.
3. Replace the model with the snapshot.
4. Replay buffered events in arrival order, deduplicated by event id and
   guarded by per-part revision.
5. Resume live application.

`ui/src/bridge/sse.ts` is a transport-agnostic SSE parser;
`ui/src/bridge/client.ts` adds Basic auth, directory scoping, reconnect
backoff, a 60 s idle watchdog and resync wiring.
`ui/src/bridge/resync.test.ts` runs 500 randomized drop scenarios
(drop start, 1–60 lost events, duplicates, live events arriving during
the snapshot fetch) and compares the final model with a reference model.
All trials pass; stale revisions cannot regress a newer snapshot.

Reproduce: `npm test` (27 tests: 11 contract + 16 bridge).

## Not yet tested

- Live STR-5: 500 real drops against a streaming prompt (needs provider
  credentials and an app/UI session). The current evidence is a
  simulation over recorded event shapes.
- Event-to-paint latency and jank (STR-2/STR-3).
- SSE behavior with Basic auth under reconnect (S5 covered one
  authenticated request, not a reconnect loop).
