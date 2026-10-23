# Phase 0 plan — repository setup, audit, feasibility

Status: **Phase 0 complete. Phase 2 in progress. G0 is blocked.**

Decision IDs in this document follow `docs/spec.md`: D1 is distribution,
D4 is the runtime profile, and D5 is the OpenCode version pin. The older
root `plan.md` uses overlapping decision numbers for a different decision
set; G0 is governed by `docs/spec.md` and this phase plan.

## G0 decision status

| Decision | Current resolution | Status at G0 | Evidence / remaining work |
| --- | --- | --- | --- |
| D1 — distribution | GitHub Releases + Obtainium first; self-hosted F-Droid after reproducible builds; Play deferred; Apache-2.0 app code. | Accepted by owner; release work remains. | `docs/adr/0001-distribution.md`. Complete the S9 legal, source-offer, signing and reproducible-build blockers before release. |
| D4 — runtime profile | Profile A remains the candidate default; Profile B is the fallback only under the documented S1/S3 rules. | **Not frozen.** Do not encode either profile as the accepted default yet. | The staged harness passes S1 loader launch and the S3 guest-shell/native-PTY comparison on one Infinix Android 16 device (4 KB pages). Full S1 server/prompt coverage, S2 comparison, required device tiers and D4 evidence rules remain open; see `docs/evidence/RESULTS.md`. |
| D5 — version pin | Keep OpenCode **1.19.3** as the exact current baseline; verify its manifest hash; reject unknown major versions. Re-evaluate only when a distributable 2.x artifact is available. | Resolved for the current baseline; 2.x compatibility is unverified. | `runtime/manifest.json`, `tests/spikes/results/S6-2.x.md`. The 2.x release-artifact check is blocked, not a reason to silently change the pin. |

**G0 is not complete.** The specification requires S1–S9, Tier A/B/C
baselines, ratified thresholds, and frozen D1/D4/D5. One physical device
has passed the staged harness's S1 loader-version check and S3 guest
shell/native-PTY probes; this does not satisfy the full S1 server and
prompt round trip or the required device matrix. S2, longer-duration
and cross-tier S7, keyboard S8, S6's 2.x artifact decision and S9
compliance items remain open. Do not start G0-dependent production
service wiring or claim gate acceptance until these blockers clear.

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

The first physical-device harness report is now recorded for one Infinix
X6870 (Android 16, arm64-v8a, 4 KB pages). The staged APK passes the
loader version check, PRoot guest shell, native 80x24 PTY comparison and
basic foreground-service start/stop. It does not cover a 16 KB-page
device, the remaining tiers, S8 keyboards or S7 background survival.
Continue gathering those results before freezing D4 or ratifying
device-dependent thresholds.

An isolated device harness now exists at `tests/spikes/harness/` and is
excluded from normal builds (`-PspikeHarness=true`). It records device
metadata, checks jniLibs ELF alignment, launches the loader/opencode
entrypoint, runs a PRoot guest shell, compares the guest shell with a
native `forkpty` session, exercises the `specialUse` foreground service
and records IME input. One staged physical-device run is recorded in
`tests/spikes/results/S3-device-harness-20261024-staged.md`; it is
partial feasibility evidence, not G0 acceptance.

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
      record passing S1 loader-version, S3 guest-shell/native-PTY and
      basic S7 lifecycle probes in `docs/evidence/RESULTS.md`.
- [ ] Complete S1 server health and mock-provider prompt round trip;
      run the required Tier A/B/C, 16 KB-page and keyboard tests, and
      collect S7 background-survival evidence.

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
| S1 | partial (host + one physical device; loader version only on device) | `tests/spikes/results/S1-host-smoke.md`, `tests/spikes/results/S3-device-harness-20261024-staged.md` |
| S2 | partial (host); device + git/npm + thermals pending | `tests/spikes/results/S2-host-profiles.md` |
| S3 | partial (host + one physical device); guest shell and native PTY pass on device, full agent-shell comparison and device matrix pending | `tests/spikes/results/S3-shell.md`, `tests/spikes/results/S3-device-harness-20261024-staged.md` |
| S4 | pass (host, mock + real provider); app/UI pending | `tests/spikes/results/S4-sse.md` |
| S5 | pass (host) | `tests/spikes/results/S5-endpoints-auth.md` |
| S6 | blocked (no 2.x release asset); 1.x drift job added, latest 1.19.4 clean | `tests/spikes/results/S6-2.x.md` |
| S7 | partial (one device; start/stop observed, background survival and tier matrix pending) | `docs/evidence/RESULTS.md` |
| S8 | partial (owner-approved user attestation of keyboard pass on Infinix Note 50s 5G using “emojikeyboard”; app/IME identity and coverage unverified; required Gboard/Samsung runs pending) | `docs/evidence/RESULTS.md` |
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
