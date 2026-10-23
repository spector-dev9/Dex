# S4 — SSE semantics (host + live real provider)

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
| Streaming text | `message.part.updated` creates an empty text part with `time.start`; tokens arrive as `message.part.delta` (`field: "text"`); a final `message.part.updated` carries the full text and `time.end` |

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
- Live token text is incremental: the bridge must append
  `message.part.delta` events, not wait only for part updates.

## Resync algorithm and simulation

`ui/src/bridge/resync.ts` implements the STR-5 algorithm:

1. On `server.connected`, buffer live events.
2. Fetch the authoritative transcript snapshot.
3. Replace the model with the snapshot.
4. Replay buffered events in arrival order, deduplicated by event id and
   guarded by per-part revision.
5. Resume live application.

Text deltas are appended to their part; a later
`message.part.updated` with a newer revision is authoritative. This
matches the live event shape above.

`ui/src/bridge/sse.ts` is a transport-agnostic SSE parser;
`ui/src/bridge/client.ts` adds Basic auth, directory scoping, reconnect
backoff, a 60 s idle watchdog and resync wiring.
`ui/src/bridge/resync.test.ts` runs 500 randomized drop scenarios
(drop start, 1–60 lost events, duplicates, live events arriving during
the snapshot fetch) and compares the final model with a reference model.
All trials pass; stale revisions cannot regress a newer snapshot. Three
additional tests cover delta accumulation and buffered delta replay.

Reproduce: `npm test` (30 tests: 11 contract + 19 bridge).

## Live STR-5 with mock provider

The host had no provider credentials, so a local OpenAI-compatible mock
provider was used to exercise the real OpenCode 1.19.3 SSE/session path
without external network or model variability.

- Provider: `test/s4-mock` via `@ai-sdk/openai-compatible` at
  `http://127.0.0.1:18081/v1`.
- Stream: 5,000 tokens (`MOCK-00000` … `MOCK-04999`), 5 ms apart.
- Harness: started `GatewayClient`, sent
  `POST /session/{id}/prompt_async`, then injected 500 synthetic SSE
  drops after the prompt started. Each reconnect fetched
  `GET /session/{id}/message` and replayed buffered events.
- Result: 501 connections, 500 drops, 3,830 received
  `message.part.delta` events, 3,832 applied events, final
  `session.idle`; 57.8 s.
- Final transcript: 5,000 tokens / 55,000 characters; last token
  `MOCK-04999`.
- The resynced `GatewayClient` model was byte-identical to the
  authoritative snapshot:
  `sha256 32ec6c0d113eab7c35e70ebf8abbe54d6535108c1cc5beeab4a89a9e0dfc07a7`.

Raw result: `results/raw/s4-live-result.json`.

## Live STR-5 with a hosted model

The same harness was repeated against the host's credential-free
`opencode` provider, using `deepseek-v4.1-flash-free`, to exercise a real
network model stream rather than the deterministic mock.

- Prompt: a 1,200-word explanation of SSE reconnection/resync, no tools.
- Result: 501 connections, 500 drops (102 during the stream, 398 after
  idle), 1,053 received `message.part.delta` events; 51.4 s.
- Final transcript: 1,250 words / 8,042 characters.
- Reconnect latency: p95 59 ms, max 287 ms, 0 failures (STR-5 requires
  ≤3 s).
- Final model byte-identical to the authoritative snapshot:
  `sha256 bcb0da016050876c3861409a36ddb1a2e6537ed4a1d7ab58ccc808ba546053a0`.

Raw result: `results/raw/s4-live-real-result.json`.

## Authenticated reconnect loop

The mock run was repeated with `OPENCODE_SERVER_PASSWORD=<ephemeral test password>`.
Unauthenticated `/global/health` returned 401; `GatewayClient` sent Basic
auth on every SSE request and the snapshot fetches used the same header.

- Result: 501 connections, 500 drops, 5,000-token transcript, 66.4 s.
- Final model byte-identical to the authoritative snapshot:
  `sha256 fee59e58c6c93e67b21399af799b3b21dbd7f0a6d356628bc8247138d29ef712`.
- No reconnect failed authentication.

Raw result: `results/raw/s4-live-auth-result.json`.

## Not yet tested

- Event-to-paint latency and jank (STR-2/STR-3).
- App/UI session integration; all live runs used the HTTP API directly,
  not the WebView/bridge UI.
