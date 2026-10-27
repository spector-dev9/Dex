# G3 acceptance status — Infinix X6870

Updated: 2026-10-27. Reference device: Infinix X6870, Android 16/API 36,
arm64-v8a, 4 KB pages, 5.58 GB RAM. Gate definition: `docs/spec.md:319`.
Machine-readable tracker: [`status.json`](status.json) (validated by
`scripts/check-g3-status.py`).

## Summary

| Status | Count |
| --- | --- |
| PASS | 6 |
| PARTIAL | 14 |
| PENDING | 12 |
| BLOCKED | 5 |
| DEFERRED (LC-5, LC-6) | 2 |
| **Total** | **39** |

G3 is **not green**. The six passing criteria are verified by CI or by tests /
probes executed on the reference device. Most remaining criteria are blocked by
the production app wiring not yet being smoke-tested on the device
(`RUNTIME OFFLINE`), not by an observed failure. The isolated spike harness adds
partial device evidence for the
runtime, PTY and auth paths; see [`spike-harness.md`](spike-harness.md).

## Criteria

| ID | Status | Evidence | Notes |
| --- | --- | --- | --- |
| RT-1 | PARTIAL | [probe](host-runtime-probe.md), [harness](spike-harness.md) | Pinned server health 200 on host; harness app-sandbox health 200 in 4.609 s; airplane-mode install not run |
| RT-2 | PARTIAL | [probe](host-runtime-probe.md), [baseline](device-baseline.md), [harness](spike-harness.md) | Host cold→healthy 4.572 s; harness server→health 4.609 s; UI cold 1756 ms / hot 81 ms; production p95 not measured |
| RT-3 | PARTIAL | [verify](verify-runtime.json), [harness](spike-harness.md) | Hashes verified on host and by the on-device installer; production startup enforcement wired, device smoke pending |
| RT-4 | PARTIAL | [harness](spike-harness.md), [audit](spike-harness-exec-audit.txt) | Harness executed loader/PRoot/OpenCode with zero `execute_no_trans` denials; production E2E not run |
| RT-5 | PASS | [verify](verify-runtime.json), [harness](spike-harness.md) | 4 KB device; ELF LOAD alignment 65536 B / 16384 B on-device; CI step added |
| STR-1 | PENDING | [probe](host-runtime-probe.md) | Heartbeat-only SSE observed; gateway request counter absent |
| STR-2 | PENDING | — | No UI latency tracing |
| STR-3 | PENDING | — | No Macrobenchmark; UI not wired |
| STR-4 | PENDING | — | Cancel path not wired |
| STR-5 | PARTIAL | `ui/src/bridge/resync.test.ts`, [probe](host-runtime-probe.md) | 500-trial simulation passes; live 500-drop run pending |
| WS-1 | PASS | [host tests](host-tests.md) | 100,000-case path safety run passed |
| WS-2 | PARTIAL | [stress](ws2-stress-300.log), [host tests](host-tests.md), [device quick](g3-device-quick-20261027.json) | 300/1000 host-JVM runs passed; spike quick 100/100 on device (corrupt=0, missing=0, sha verified); FULL 1000 SIGKILL device run pending |
| WS-3 | PARTIAL | [host tests](host-tests.md) | Stale-precondition conflict covered; open/save flow pending |
| WS-4 | PARTIAL | [host tests](host-tests.md) | 1 GiB bounded reads pass; latency/memory thresholds not measured |
| WS-5 | PASS | [host tests](host-tests.md) | 60/60 operation matrix passed |
| WS-6 | BLOCKED | — | No git service implementation |
| TERM-1 | BLOCKED | — | `ptyService` is null; terminal not wired |
| TERM-2 | BLOCKED | [harness](spike-harness.md) | Production PTY service absent; harness `--kill-on-exit` cleanup passed |
| TERM-3 | BLOCKED | [harness](spike-harness.md) | Production PTY service absent; harness PTY reported 80x24 + env parity |
| TERM-4 | BLOCKED | — | Needs wired PTY service |
| TERM-5 | PARTIAL | [harness](spike-harness.md) | Scripted PTY bytes pass (text, Enter, Backspace, Tab, Esc, Up, Ctrl-C, Alt-x); Gboard/UI untested |
| LC-1 | PENDING | — | Needs wired UI/runtime |
| LC-2 | PENDING | — | Needs wired runtime |
| LC-3 | PASS | [host tests](host-tests.md), [device raw](device-lc3-20261027-raw.txt) | Host supervisor crash tests pass; on-device 50/50 SIGKILL restarts healthy, p95 4,132 ms, sessionsLost=0 |
| LC-4 | PARTIAL | [idle raw](idle-top-60s-raw.txt), [device raw](device-lc4-20261027-raw.txt) | Zero-interaction 10-min run: stop-after-idle passes (postStopTicks=0); idle server CPU 3.458% avg vs 1.0% threshold — per-thread investigation queued |
| LC-5 | DEFERRED | `docs/spec.md` | Post-v1 optional per gate |
| LC-6 | DEFERRED | `docs/spec.md` | Post-v1 optional per gate |
| SEC-1 | PARTIAL | [auth summary](../../../tests/spikes/results/raw/g3-host-auth-summary.json), [harness](spike-harness.md) | Routes 401 without secret / 200 with Basic; harness authenticated from its sandbox; second-UID client not run |
| SEC-2 | PARTIAL | [baseline](device-baseline.md), [audit](spike-harness-exec-audit.txt), [device quick](g3-device-quick-20261027.json) | No secret patterns; no exec denials (25 vendor perf_ioctl only); on-device scan 0 findings, 3/3 denied paths; full E2E scan pending |
| SEC-3 | PASS | `scripts/check-release-manifest.py`, CI | Release manifest invariants enforced and green |
| SEC-4 | PENDING | — | Permission prompts need runtime/UI wiring |
| SEC-5 | PARTIAL | `docs/licenses/README.md`, CI | SBOM/licenses/pins green; osv-scanner and source offer missing |
| SEC-6 | PENDING | `docs/spec.md` | v1.1 remote profile |
| REL-1 | PENDING | — | No two-build diffoscope comparison |
| REL-2 | PENDING | — | No upgrade/install docs or downgrade test |
| REL-3 | PENDING | — | No release signing config |
| REL-4 | PASS | CI drift run `40496782526`, [probe](host-runtime-probe.md) | Nightly drift green; live `/doc` byte-identical to pin |
| QA-1 | PARTIAL | [host tests](host-tests.md) | 39 tests pass; coverage/lint thresholds unmeasured |
| QA-2 | PENDING | — | No nightly device E2E workflow |

## What would unblock the rest

1. Wire `MainActivity`/`OpenCodeShell` to the runtime manager and a real
   `AndroidRuntimeProcessLauncher`, install the pinned runtime, and start the
   supervised server (unblocks RT-1/2/3/4, STR-*, TERM-*, LC-1/2/4).
2. Implement the terminal/PTY service and pass it into `OpenCodeShell`
   (unblocks TERM-1..5).
3. Implement the git service behind `core/git-api` (unblocks WS-6).
4. Add release signing, two-build reproducibility, upgrade docs (REL-1/2/3) and
   osv-scanner + source offer (SEC-5).
5. Run the remaining section-7 scripts on the app-sandbox build and record the
   results in this folder.
