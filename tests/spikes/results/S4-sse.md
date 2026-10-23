# S4 — SSE semantics (partial)

Date: 2026-10-23. Server: pinned 1.19.3 on 127.0.0.1, no password.
Capture: raw socket, 25 s, then reconnect after 1 s for 6 s.

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

Raw evidence: `results/raw/sse-no-auth-event.sse`,
`results/raw/sse-no-auth-event-reconnect.sse`,
`results/raw/sse-no-auth-summary.json`.

## Implications

- A 60 s idle watchdog is safe against a ~10 s heartbeat.
- Reconnect must treat each `server.connected` as a resync trigger; it
  is not a replay and does not carry a cursor.
- The gateway must send Basic auth on the SSE request when a password
  is configured (verified in S5).

## Not yet tested

- Directory scoping (`/event?directory=…`) — the earlier ad-hoc attempt
  was lost to a shell timeout; rerun with the probe script.
- Ordering, duplicates and resync under 500 injected drops (STR-5).
- Event-to-paint latency and jank (STR-2/STR-3).
