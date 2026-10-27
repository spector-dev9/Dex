# G3 pinned-runtime probe on the reference device

Date: 2026-10-27T11:44Z. Device: Infinix X6870 (Android 16/API 36, arm64-v8a),
running from Termux (unconfined host), **not** the app sandbox.

## Method

```bash
python3 scripts/fetch-runtime.py --alpine --alpine-runtime-libs
python3 scripts/verify-runtime.py
python3 tests/spikes/scripts/probe_server.py --label g3-host-auth --password g3-test-secret
```

The pinned archive was extracted and run through its bundled musl loader
(`ld-musl-aarch64.so.1 --library-path …`). Alpine `libgcc_s.so.1` and
`libstdc++.so.6` were staged from the pinned packages.

## Results

| Check | Result |
| --- | --- |
| Runtime version | `opencode --version` → 1.19.3 |
| Artifact SHA-256 | `39c9ff1e…d0b3` (verified by `fetch-runtime.py`) |
| Binary SHA-256 | `37c7de55…9ecd` (matches `runtime/manifest.json`) |
| ELF LOAD alignment | 65536 B minimum (`verify-runtime.py` pass) |
| Cold start to `/global/health` | **4.572 s**, HTTP 200 (RT-2 host-side) |
| Auth matrix | health, doc, project, session, PTY, event: **401** without/wrong/Bearer/empty-user auth, **200** with Basic `opencode:<password>` |
| SSE first event | `server.connected` at 0.011 s |
| SSE heartbeat | `server.heartbeat` at 10.013 s (≈10 s cadence) |
| SSE reconnect | new `server.connected` event delivered |
| Live `/doc` | byte-identical to `contract/openapi/opencode-1.19.3.json` (`eebb5604…417b`) |

Raw evidence:

- [`verify-runtime.json`](verify-runtime.json)
- `tests/spikes/results/raw/g3-host-auth-summary.json`
- `tests/spikes/results/raw/g3-host-auth-server.log`
- `tests/spikes/results/raw/g3-host-auth-event*.sse`

## Caveats

This proves the pinned artifact boots and behaves correctly on the reference
device under the unconfined Termux host. It does **not** prove app-sandbox
execution (`execute_no_trans`, targetSdk 36 W^X), app-sandbox cold-start
thresholds, UI/streaming latency, or the full security matrix from a
second-UID instrumented app. Those remain G3 pending items.
