# G3 host-test evidence

Date: 2026-10-27. Executed on the Infinix X6870 (the reference device) inside
Termux with JDK 21 and Gradle 8.13. These are JVM tests, not app-sandbox
instrumentation tests.

## Unit and property tests

```bash
./gradlew --no-daemon -PskipApp=true \
  :core:workspace:test :core:security:test :core:supervisor:test \
  :core:bridge-api:test :core:model:test :core:opencode-client:test
```

Result: **BUILD SUCCESSFUL** in 5m 18s, **39 tests, 0 failures, 0 errors**.

| Module | Tests | Criteria supported |
| --- | --- | --- |
| `core:workspace` | 12 | WS-1 (100,000-case path safety), WS-3, WS-4, WS-5 (60-case matrix) |
| `core:security` | 5 | SEC-1 (loopback Basic-auth provisioning/verification) |
| `core:supervisor` | 4 | LC-3 (restart, health timeout, crash-loop breaker) |
| `core:bridge-api` | 3 | G1 bridge contract/dispatch |
| `core:model` | 3 | pinned model decoding |
| `core:opencode-client` | 12 | RT-3 (client pin/limits), STR-5 transport/reconnect primitives |

## Atomic-write SIGKILL stress

```bash
./gradlew --no-daemon -PskipApp=true :core:workspace:workspaceAtomicWriteStress \
  -PworkspaceAtomicWriteRuns=300
```

Result: **PASS: 300 SIGKILL interruptions; payload sizes 1024..52428800 bytes**
in 2m 40s (WS-2). The criterion calls for 1,000 runs; the remaining 700 and the
app-sandbox variant are pending.

Raw log: [`ws2-stress-300.log`](ws2-stress-300.log).

## Stream / resync simulation

`npm test` (CI contract job) runs `ui/src/bridge/resync.test.ts`: 500 randomized
drop scenarios pass, covering STR-5's snapshot/replay algorithm. The live
500-drop device run is still pending.
