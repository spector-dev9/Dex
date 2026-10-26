# Phase 0 plan — repository setup, audit, feasibility

Status: **Phase 0 evidence audit complete. Phase 2 in progress. G0 is blocked.**

Decision IDs in this document follow `docs/spec.md`: D1 is distribution,
D4 is the runtime profile, and D5 is the OpenCode version pin. The older
root `plan.md` uses overlapping decision numbers for a different decision
set; G0 is governed by `docs/spec.md` and this phase plan.

## G0 decision status

| Decision | Current resolution | Status at G0 | Evidence / remaining work |
| --- | --- | --- | --- |
| D1 — distribution | GitHub Releases + Obtainium first; self-hosted F-Droid after reproducible builds; Play deferred; Apache-2.0 app code. | Accepted by owner; release work remains. | `docs/adr/0001-distribution.md`. Complete the S9 legal, source-offer, signing and reproducible-build blockers before release. |
| D4 — runtime profile | Profile A remains the candidate default; Profile B is the fallback only under the documented S1/S3 rules. | **Not frozen.** Do not encode either profile as the accepted default yet. | Two corrected physical harness summaries verify Node guest-shell/native-PTY feasibility and S1 mock-provider round trips on Android 16/arm64 devices with 4 KB pages. Profile A/B comparison, representative device tiers, production agent-shell wiring, and D4 evidence rules remain open; see `docs/evidence/RESULTS.md`. |
| D5 — version pin | Keep OpenCode **1.19.3** as the exact current baseline; verify its manifest hash; reject unknown major versions. Re-evaluate only when a distributable 2.x artifact is available. | Resolved for the current baseline; 2.x compatibility is unverified. | `runtime/manifest.json`, `tests/spikes/results/S6-2.x.md`. The 2.x release-artifact check is blocked, not a reason to silently change the pin. |

**G0 is not complete.** The specification requires S1–S9, Tier A/B/C
baselines, ratified thresholds, and frozen D1/D4/D5. Two physical device
summaries (`docs/evidence/Device 1` and `docs/evidence/Test 2`) report S3
guest-shell/native-PTY behavior with Node.js 20.15.1 and successful S1
health/mock-provider flows on the same APK, on Android 16 arm64 devices
with 4 KB pages. These are feasibility results, not the required device
matrix or production-app acceptance. Earlier S7 reports were exported
about 13 minutes and 25 seconds after start, not at confirmed service
death. A later 10-minute Infinix harness diagnostic reports 631,745 ms of
service survival with zero restarts; the child remained alive at stop, while
sparse samples were classified by the owner as Android scheduling behavior.
It did not establish continuous screen-off or active-agent survival; see
`tests/spikes/results/S7-soak-20261026-041939.md`. S2,
representative Tier A/B/C coverage, a 16 KB-page device, S7 and
native-terminal TERM-5, S6's 2.x artifact decision, and S9 compliance remain
open. The Gboard field-input S8 test is owner-reported green.
Do not start G0-dependent production service wiring or claim gate
acceptance until these blockers clear.

### G0 sequencing constraint

The gate currently has a dependency cycle: it requires device evidence
from S1/S3 (and lifecycle/keyboard evidence from S7/S8), while product
runtime and PTY implementations are deferred until after G0. Break this
without prematurely freezing production architecture by building only a
disposable spike harness under `tests/spikes/` (or a clearly isolated
debug-only target). It may prove native-library launch, PRoot guest shell,
PTY behavior, lifecycle and keyboard feasibility; it must not become
production service wiring or be treated as acceptance until run on the
required devices.

The earlier physical-device harness report used the pre-official package.
A later report for one Infinix X6870 (Android 16, arm64-v8a, 4 KB pages)
records successful official-version loader and basic foreground-service
start/stop probes. Its claimed S3 pass is invalid because output contained
`node: not found` and literal `******` in place of the compared values.
It records S8 text changes but no key-down events (expected for a soft IME). The report does not
contain an archive hash, and does not cover a 16 KB-page device, the
remaining tiers, native-terminal TERM-5 or S7 background survival.
That invalidated report is superseded for S3 by later reports summarized in
`docs/evidence/Device 1` and `docs/evidence/Test 2`, which verify Node and
matching guest/PTY values on two devices. Continue gathering the remaining
tiers, native-terminal TERM-5, S7 survival and other device results before
freezing D4 or ratifying device-dependent thresholds.

An isolated device harness now exists at `tests/spikes/harness/` and is
excluded from normal builds (`-PspikeHarness=true`). It records device
metadata, checks jniLibs ELF alignment, launches the loader/opencode
entrypoint, runs a PRoot guest shell, compares the guest shell with a
native `forkpty` session, exercises a timed `specialUse` foreground-service
probe with a partial wake lock, separate alarm heartbeat and power-state
telemetry, and records IME input. It remains a harness-service diagnostic;
it does not stand in for an active agent task or S7/LC-2 acceptance. One
staged physical-device run is recorded in
`tests/spikes/results/S3-device-harness-20261024-staged.md`; it is
partial feasibility evidence, not G0 acceptance.

### Lifecycle requirements before S7 acceptance

Production should hold a foreground service and partial wake lock only
while an agent task is active, explain any battery-optimization exemption
request, document OEM autostart/background controls (including Infinix and
Nothing), and release resources when idle. On resume, reconcile process and
session health, reconnect SSE from the last event ID, and re-fetch session
state. PRoot/Node child-process survival under Android 12+ phantom-process
killing is a separate risk and must not be inferred from service survival.
The harness can diagnose CPU suspend versus service/process interruption;
it does not implement these production lifecycle behaviors.

## Foundation status — what works and what is blocked

**Working and verified in the repository or host environment**

- Kotlin core API contracts compile; compiled TypeScript/Kotlin bridge
  parity covers protocol v1, 47 commands, errors, timeouts and topics.
- TypeScript bridge parser, reconnect/resync and contract checks have
  host-side test coverage; host S4 and S5 spike results are recorded.
- The minimal Android shell and debug APK have compiled in this
  environment with Java 21 and the Android SDK. The app-local fake
  service/partial-handler harness has seven passing unit tests; it is
  test-only and does not constitute production service wiring.
- Repository architecture and provisional-annotation checks pass.

**Still blocked or not yet accepted**

- One-device harness evidence now passes the loader `--version`, guest
  shell, native PTY and foreground-service start/stop probes. Full S1
  server/prompt coverage, S2 comparisons, Tier A/B/C baselines, 16 KB
  page-size device coverage, lifecycle/thermal/soak and keyboard
  acceptance remain pending; the run does not promote acceptance rows.
- Runtime packaging/supervision, authenticated ephemeral loopback
  lifecycle, production Kotlin HTTP/SSE gateway, native workspace
  operations and native interactive PTY are not implemented.
- App/UI SSE recovery and event-to-paint measurements are pending.
- S6 needs a published 2.x distributable artifact. S9 has unresolved
  LGPL/source-offer, dependency-closure and release-compliance items.
- The command handler and fake services are partial test fixtures;
  command payload DTOs and production wiring remain deferred until G0.

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
- S9: distribution decision accepted (2026-10-23; D1 in `docs/spec.md`) —
  GitHub Releases + Obtainium, a
  self-hosted F-Droid repo after reproducible builds, bundled runtime,
  Play deferred, app code Apache-2.0. Compliance checklist is in
  `docs/licenses/`.
- S9 artifacts generated: 16 Alpine packages, 45 Bun-bundled
  libraries/polyfills, pinned PRoot/libtalloc/libandroid-shmem, a
  68-component main CycloneDX 1.5 SBOM, an 849-package OpenCode
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
- [ ] Production bridge handler, fake service implementations and command
      payload DTOs (after G0). A test-only fake-services/partial-handler
      harness and focused unit tests exist; they are not production wiring
      or device-acceptance evidence.
- [ ] G1 acceptance (RT-1..RT-5, SEC-1/2, STR-1/5, LC-3, WS-1/2) on
      devices.

See `docs/architecture/g1-contracts.md`.

## Phase 2 device harness scaffold (2026-10-24)

- [x] Add `tests/spikes/harness/`, a debug-only Android app gated by
      `-PspikeHarness=true`; normal app and core builds do not include it.
- [x] Add `scripts/stage-spike-harness.py` to fetch/verify the pinned
      runtime, PRoot, talloc, shmem and Alpine rootfs, stage the
      jniLibs entrypoints, and produce a `run-as`-extractable archive.
- [x] Update the PRoot pin from 5.1.107.98 to 5.1.107.100 because the
      Termux pool no longer serves the .98 deb (HTTP 404); regenerate
      the license inventory and SBOM. Host S1/S3 evidence predates this
      pin change.
- [x] Update the PRoot pin from 5.1.107.100 to 5.1.107.101 on 2026-10-25
      because the Termux pool no longer serves the .100 deb (HTTP 404);
      regenerate the license inventory and SBOM. Evidence recorded before
      this pin change used .100.
- [x] Add harness actions for S1 loader launch, S3 PRoot guest shell,
      S7 `specialUse` foreground-service lifecycle, S8 IME input and a
      JSON report. They are implemented; the staged S1/S3/S7 device
      results are recorded below and in the evidence matrix.
- [x] Add a test-only native `forkpty` comparison for guest `PWD`,
      `PATH`, Node version and 80x24 terminal sizing. Keep it isolated
      from production wiring.
- [x] Add Android CI compile support and verify the gated
      `arm64-v8a` debug APK, including the native helper.
- [x] Run the staged harness on one physical Android 16/arm64 device;
      record S1 loader-version and basic S7 lifecycle probes in
      `docs/evidence/RESULTS.md`. Its originally reported S3
      guest-shell/native-PTY passes were later invalidated because Node
      was missing and the probe accepted unverified output.
- [x] Add an isolated harness action for authenticated S1 server health
      and a deterministic local mock-provider prompt round trip; the
      physical report records one successful device run.
- [x] Capture one user-reported S1 server-health/session/mock-provider
      prompt round trip; see
      `../../tests/spikes/results/S1-session-roundtrip-user-log.txt`.
- [x] Run the corrected S3 guest-shell/native-PTY probes on two physical
      devices; see `docs/evidence/Device 1` and `docs/evidence/Test 2`.
- [x] Run the exploratory S7 30-minute foreground-service soak on both
      devices; both runs ended below 30 minutes and remain failures.
- [ ] Complete the S1 device matrix, Tier A/B/C and 16 KB-page tests,
      production agent-shell comparison, keyboard acceptance, and
      agent-task S7 background-survival evidence.

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
| S1 | partial (host + two physical devices; loader, health, and mock prompt on both) | `tests/spikes/results/S1-host-smoke.md`, `docs/evidence/Device 1`, `docs/evidence/Test 2` |
| S2 | partial (host); device + git/npm + thermals pending | `tests/spikes/results/S2-host-profiles.md` |
| S3 | partial (two physical harness summaries verify Node and matching guest/PTY fields; production agent-shell wiring and device matrix pending) | `tests/spikes/results/S3-shell.md`, `docs/evidence/Device 1`, `docs/evidence/Test 2`, `docs/evidence/RESULTS.md` |
| S4 | pass (host, mock + real provider); app/UI pending | `tests/spikes/results/S4-sse.md` |
| S5 | pass (host) | `tests/spikes/results/S5-endpoints-auth.md` |
| S6 | blocked (no 2.x release asset); 1.x drift job added, latest 1.19.4 clean | `tests/spikes/results/S6-2.x.md` |
| S7 | partial (30-minute exploratory soak attempted on two devices; both failed the duration threshold; agent-task and tier matrix pending) | `docs/evidence/Device 1`, `docs/evidence/Test 2`, `docs/evidence/RESULTS.md` |
| S8 | partial (two devices identify Gboard and record text changes; zero hardware key-down events is expected for soft IMEs and is not a failure; acceptance not assessed; Samsung run pending) | `docs/evidence/Device 1`, `docs/evidence/Test 2`, `docs/evidence/RESULTS.md` |
| S9 | distribution decision accepted; SBOM/inventory done, compliance blockers open | `docs/adr/0001-distribution.md`, `docs/licenses/` |

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

1. **Extend S1 feasibility:** run the pinned server on device, verify
   `/global/health`, and complete one mock-provider prompt round trip;
   current physical evidence only verifies `opencode --version`.
2. **Finish device evidence:** acquire Tier A/B/C, 16 KB-page and
   keyboard targets; record device metadata and S7 background-survival
   results in `docs/evidence/RESULTS.md`.
3. **S6:** run the contract suite against a 2.0.x artifact when one is
   published; `v2.0.32`/`v2.1.3` tags exist but have no release assets
   or npm builds as of 2026-10-23.
4. **S9:** resolve Bun/libtalloc LGPL compliance, verify the OpenCode
   closure against the binary, review Alpine packages without separate
   copyright files, and complete the remaining checklist (trademark,
   developer verification).
5. **G0 decision close:** apply the D4 evidence rules, ratify numeric
   thresholds from measured device baselines, and record the final gate
   decision. D1 is already accepted and D5 is pinned for the current
   baseline.
6. **After G0:** begin production service wiring and command DTOs; then
   validate app/UI bridge abort/reconnect behavior and event-to-paint.

## Outstanding device evidence

The latest official-package report is
`docs/evidence/spike-20261024-153116.json`. It is a single pre-G0 harness
run, not device-matrix acceptance. Its probe count must not be used to
infer completion of the following tests:

| Blocked run | Missing evidence | Required next action |
| --- | --- | --- |
| S1 authenticated server and prompt | The report proves loader execution, not authenticated `/global/health` or a mock-provider prompt round trip on a physical target. | Run the harness's authenticated-server action on each required Tier A/B/C device and preserve the report and APK/artifact hashes. |
| S2 runtime profiles | No comparable per-device cold start, idle PSS/RSS, 10k-file Git status, agent `npm test`, or thermal results for Profiles A and B. | Execute the prescribed workloads on Tier A/B and record repeated measurements before deciding D4. |
| Tier coverage and RT-5 | Only Infinix X6870, Android 16, arm64-v8a, 4096-byte pages is represented. No Tier B/C or 16384-byte-page boot evidence exists. | Acquire and test the specified targets; include page size, ABI, thermal state, run count, packaged artifact hashes, and ELF alignment results. |
| S7 / LC lifecycle | One-device foreground-service start/stop is present; background task survival, crash recovery, soak and thermal acceptance are absent. | Run the required duration/repetition on Tier A/B/C and classify failures from diagnostics. |
| S8 / TERM-5 keyboard | The owner-reported Gboard field-input test is green and records text, composing, IME-key, hardware-key, selection, and context-menu events. Its `keyboardAcceptance=assessed` label conflicts with the matching APK/source `not-assessed` string. | Keep S8 field-input green as owner-reported; separately verify native-terminal TERM-5 with Gboard. |
| S6 / 2.x | No distributable OpenCode 2.x artifact is available to test. | Keep the 1.19.3 pin and run contract tests only when a distributable candidate exists. |

Device rows in `docs/evidence/RESULTS.md` therefore remain `pending`.
Host builds, host runtime tests, the one-device harness and emulator
availability are separate evidence classes and do not promote those rows.
