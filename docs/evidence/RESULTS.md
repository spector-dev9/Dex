# Acceptance evidence

Status vocabulary:

- `pending`: not started or no admissible run.
- `partial-host`: host-side evidence only; does not satisfy a device or app requirement.
- `emulator`: measured on an emulator in CI.
- `measured`: measured on the Infinix X6870 reference device.
- `green-reported`: owner-reported probe/test passed; does not promote a
  separate G0 row by itself.

Every row starts as `pending`. A row may move only when its evidence is
linked or appended; host spikes cannot promote device rows.

## Host-only evidence

- `:core:workspace:workspaceAtomicWriteStress` passed 1,000 host-side
  SIGKILL interruptions with payloads from 1 KiB through 50 MiB. This is
  durability evidence for the host filesystem/test harness only; it does
  not satisfy target-device or app-integration requirements.
- `:core:workspace:test` includes a sparse 1 GiB file range-read check that
  requests 16 bytes near EOF and validates the returned bytes. It verifies
  bounded range behavior on the host; it is not a measured memory or latency
  result on Android.
- `:core:runtime:test` covers HTTPS-only downloads, redirect constraints,
  bounded transfer size, SHA-256 verification, cleanup on failures, and a
  manifest-driven pinned OpenCode, Alpine rootfs, and Alpine runtime-library
  installation path. Package tests cover digest rejection and rootfs/package
  path containment. These host tests do not establish device networking,
  PRoot native packaging, production runtime launch, or supervision.
- App unit tests exercise encrypted-file storage mechanics through a test
  cipher. They do not exercise Android Keystore hardware/software providers,
  device lifecycle, or production listener wiring.

## Reference-device matrix

**G0 reference hardware: Infinix X6870 only** (INFINIX, Android 16/API 36,
arm64-v8a, 4 KB pages, 5.7 GB RAM, Gboard). Tier A/B/C, 16 KB-page devices,
Samsung Keyboard and other hardware are out of scope for v1. Historical
two-device records are retained below as feasibility evidence only.

| Requirement | Scope | Status | Evidence | Blocker |
| --- | --- | --- | --- | --- |
| S1 | Reference device | pending | The harness reports OpenCode 1.19.3 loader, authenticated health, and complete mock-provider prompt round trips on the Infinix X6870 (see [Device 1](Device%201) and the latest raw report). | production app/runtime implementation |
| S2 cold start | Reference device, Profile A/B | pending | none | no app implementation; Profile B not implemented |
| S2 idle RSS | Reference device, Profile A/B | pending | none | no app implementation; Profile B not implemented |
| S2 git status, 10k files | Reference device, Profile A/B | pending | none | no app implementation; Profile B not implemented |
| S2 npm test | Reference device, Profile A/B | pending | none | no app implementation; Profile B not implemented |
| S3 | Reference device | pending | The Infinix X6870 reports `N=v20.15.1`, `GUEST-OK`, and matching native-PTY environment values; the latest report also passes the 11/11 harness suite. | production agent-shell wiring and comparison against actual agent bash |
| S6 | OpenCode 2.0.x | pending | none | no 2.x distributable artifact |
| S7 | Reference device | pending | The 2026-10-26 diagnostic passes the 10-minute service-survival check (631,745 ms, zero restarts); the child was alive at stop. It was not an active-agent, screen-off run. | one real agent task with screen off for 10 minutes |
| S8 | Gboard field input | green-reported | The owner-reported [Gboard test](../../tests/spikes/results/S8-ime-observations-20261025.md) is green for field input. It is EditText-only with `keyboardAcceptance=not-assessed`. | PTY-backed TERM-5 remains pending |
| RT-1 | Reference device, offline | pending | none | installable app unavailable |
| RT-2 | Reference device startup | pending | none | runtime implementation unavailable |
| RT-5 | 16 KB-page device | out-of-scope | none | reference device has 4 KB pages; not a v1 gate |
| LC-2 | Reference device, 10 min active task | pending | The 2026-10-26 diagnostic is a service check, not an active-agent run. See [S7 diagnostic](../../tests/spikes/results/S7-soak-20261026-041939.md). | one active-agent survival run with screen off |
| LC-5 | Reference device, 4 h soak | optional | none | post-G0; not a G0 blocker |
| LC-6 | Reference device thermal | optional | none | post-G0; not a G0 blocker |
| TERM-5 | Native terminal input, Gboard | pending | The 2026-10-26 report passes three scripted PTY byte round-trips on the Infinix X6870, including text, Enter/ICRNL, Backspace, Tab, Esc, Up, Ctrl-C and Alt-x; this validates byte transport only. | exercise the native terminal UI with Gboard, including paste/select, extra keys, resize, signals, and cleanup |
| STR-5-live-device | Reference device, 500 drops | pending | host runs only | app/UI transport unavailable |

## Notes

- The earlier physical-device runs predate the current official
  OpenCode package and remain historical evidence. The official-package
  `spike-20261024-080149.json` report records OpenCode `1.19.3`,
  Alpine `3.20.3`, successful loader and foreground-service start/stop
  probes on an Infinix X6870 running Android 16 with 4 KB pages. Its
  guest-shell/native-PTY passes are invalidated because the probe did not
  establish Node or valid environment values. The report does not include
  the staged archive SHA-256, so it does not independently prove
  byte-for-byte correspondence with the pinned artifact.
- The earlier S8 results confirm text changes with zero `keyDownEvents`,
  which is expected for a soft keyboard. The later owner-reported Gboard
  field-input test is green and its counters are recorded separately. The
  harness also captures `commitText`, composing, deletion, IME key,
  editor-action, content-commit, selection and context-menu events. Native
  terminal TERM-5 remains pending. The `16KB-ok`
  strings describe native ELF load alignment; this device reports
  4096-byte pages and is not 16 KB-device evidence.
- The `partial-host` value is retained for existing spike records under
  `tests/spikes/results/`; no row above is promoted by those records.
- S2 remains `pending`: the app has no Profile B implementation and no
  Profile B device measurements. The host profile spike is directional
  only.
- `tests/spikes/harness/` can collect S1/S3/S7/S8 device evidence, but
  it is an isolated pre-G0 harness. Running it does not promote a row
  until the report and device metadata are attached here.
- The first physical device run is recorded in
  `tests/spikes/results/S3-device-harness-20261024.md`. It confirms the
  staged runtime layout and S7 foreground-service lifecycle, but S1/S3
  are blocked because the installed APK was the compile-only CI artifact.
  A harness APK must be built after staging so the loader and PRoot are in
  `nativeLibraryDir`; app-private exec is blocked by Android.
- Second physical device run on 2026-10-24
  (`tests/spikes/results/S3-device-harness-20261024-staged.md`): the
  rebuilt post-staging APK passes S1 loader execution
  (`opencode --version`, exit 0, stdout `1.19.3`) and the S7 service
  lifecycle completes. Its PRoot guest-shell and native `forkpty` results
  were reported as passing but are invalidated by the false-pass defect
  documented below. The initial side-load again showed the whole
  runtime payload missing from `files/spike`; fixed by pushing the
  pinned `spike-runtime.tar.gz` and extracting with
  `adb shell run-as` (no root). Rows stay `pending` because this is the
  isolated pre-G0 harness.
- A separate user-shared S8 harness trace recorded text updates while
  entering `opencode mobile test` and one `KEYCODE_DEL` (67) event. It
  shows basic IME text/edit observation only. The user later reported a
  passing keyboard test on an Infinix Note 50s 5G using “emojikeyboard”
  and, as project owner, approved the test. This records owner approval
  of the reported test, not independent verification: no separate trace,
  keyboard app/IME identity or coverage details were provided. The
  2026-10-24 04:22 confirmation JSON is an S1/S3/S7 repeat run and
  explicitly says S8 was not exercised. This historical report predates the
  owner-reported green Gboard field test; native-terminal TERM-5 remains open.
- A repeat run of the same day
  (`raw/spike-device-20261024-042222.json`) reported all 13 harness
  records passing again. The S3 results are invalidated by the same
  probe defect; this repeat does not make them admissible.
- The official-package rerun is a separate report at
  `raw/spike-20261024-080149.json`. It passes the staged runtime layout,
  OpenCode loader version and foreground-service start/stop. Its claimed
  PRoot guest-shell and native PTY passes are invalidated by the defect
  noted below. It records five S8 text changes but no
  key-down events. This remains partial harness evidence: S1 server and
  prompt behavior, long-duration service survival, reference-device coverage, and
  full S8 acceptance are still outstanding.
- The runtime-download rerun at `spike-20261024-153116.json` records
  15/15 passing results on the same Infinix X6870 / Android 16 device.
  The pinned OpenCode, Alpine rootfs, `libgcc`, and `libstdc++` archive
  hashes match the manifest; the download/install action took 39.05 s.
  The preceding attempt had already completed and cached these archives
  before Alpine APK extraction failed on PAX metadata, so this rerun is
  not a cold-network speed measurement. Loader and service lifecycle
  probes pass again. The S3 probes are
  invalidated by the same false-pass defect. S8 records seven text changes and zero key-down
  events. This remains pre-G0 evidence on the reference device; it does not
  satisfy the full G0 evidence or named keyboard acceptance. The separate
  S1 diagnostics prove one mock prompt round trip, but not the required
  reference-device G0 evidence.
- The later user-shared Infinix X6870 / Android 16 report is not admissible
  as an S3 pass: its guest output reports `node: not found` and contains
  literal `******` instead of the requested environment values, while the
  native PTY record still says `environmentMatch=true`. The Kotlin and
  native probes previously used a `printf` format with no conversions and
  accepted incomplete marker data. The harness now emits explicit `D=`,
  `P=` and `N=` values, requires all three values and a valid Node version,
  and fails both probes when Node is unavailable. The manifest now pins
  Alpine Node.js 20.15.1 arm64/musl and its shared-library dependency
  packages; the host stage and on-device installers place the executable at
  `rootfs/usr/bin/node`. All package hashes are verified, and host ELF
  inspection confirms the required shared objects are staged. That report
  is superseded for S3 by the corrected physical report below.
- In the same later report S7 start/stop was observed, and S8 recorded
  text changes but `keyDownEvents=0`; this does not satisfy keyboard
  acceptance. The harness now labels future records `S8-ime-observation`
  and states that keyboard acceptance is not assessed. Separately supplied
  [S1 diagnostics](../../tests/spikes/results/S1-session-roundtrip-user-log.txt)
  recorded health HTTP 200,
  successful session creation, accepted async prompt submission, and one
  mock-provider request. This single harness path is useful evidence but
  does not close the reference-device G0 evidence.
- The corrected physical report
  (`spike-20261025-003215.json`) first verified S3 on an Infinix X6870 /
  Android 16 / arm64 / 4 KB-page device: the guest reported `N=v20.15.1`
  and `GUEST-OK`, and native PTY comparison reported `environmentMatch=true`
  with the same D/P/N values. It also recorded S1 health and a mock prompt.
  Subsequent two-device runs are summarized below; they preserve the same
  S1/S3 feasibility result but add failed exploratory S7 durations and
  incomplete S8 evidence.
- Two subsequent device summaries are available:
  [Device 1](Device%201) is an Infinix X6870 (Android 16, arm64, 4 KB
  pages); [Device 2](Test%202) is a Nothing A001T / Galaxian (Android 16,
  arm64, 4 KB pages). Both identify the same debug APK SHA-256
  (`a5e0deb3ab7e6bf61983803a3a8ea3908d3aa064186d449f286d26e7105980b6`),
  report successful pinned runtime installation, S1 loader/health/mock
  prompt, and S3 guest Node/PTY checks. Each has two complete S1 mock
  round trips in the supplied diagnostics; Device 2 also has one truncated
  probe. Device 1's latest S7 soak ended at 782,647 ms (~13 minutes) and
  Device 2's at 25,237 ms (~25 seconds). Those exploratory exports are
  historical and outside the v1 gate; the reports state
  `screenOff=not-measured`, so they are not LC-2 acceptance.
  Both S8 records identify Gboard, show text changes but zero key-down
  events, and mark keyboard acceptance not assessed. These results add
  historical two-device feasibility data only; Device 2 and 16 KB-page
  targets are out of scope for v1, and the runs do not establish the full
  reference-device G0 evidence.
- G0 remains open; no acceptance value here freezes D4 or D5.
- The user-reported [S1 server/mock smoke](../../tests/spikes/results/S1-server-mock-20261024.md)
  is green for two complete authenticated-health and mock-prompt runs. The
  logs omit device identity, Android version, APK hash, and subsequent S3
  results, so the reference-device S1 rows remain pending and G0 is not promoted.
- The reported [S8 Gboard test](../../tests/spikes/results/S8-ime-observations-20261025.md)
  is owner-reported green on an Infinix X6870. The APK hash matches the
  rebuilt harness, and `keyboardAcceptance=not-assessed` is intentional for
  this EditText-only observation. It does not test PTY-backed TERM-5 input,
  extra-key mappings, or the Gboard requirement; native-terminal TERM-5
  remains outstanding.
- The fixed-harness S1/S3 suite on 2026-10-25
  ([results](../../tests/spikes/results/S1-S3-device-suite-20261025.md), raw
  `tests/spikes/results/raw/spike-device-suite-20261025-113641.json`) passes
  10/10 available S1/S3 probes on the Infinix X6870 / Android 16 / arm64 /
  4 KB pages: native-entrypoint load-align checks, staged runtime layout,
  S1 loader, authenticated health, mock prompt, S3 guest shell and the
  native-forkpty comparison. The first attempt crashed after S1 in the new
  server-probe drain thread (`InterruptedIOException` after `destroy()`
  closed the stream); the harness drain now tolerates teardown and the
  rerun records a clean shutdown with no leftover server processes. This is
  the same pre-G0 single-device feasibility class as the runs above; it
  does not establish the reference-device G0 evidence or keyboard acceptance.
- The 2026-10-26 device report
  (`spike-20261026-064348.json`) passes the available S1/S3 and scripted
  TERM-5 partial suite 11/11 on an Infinix X6870 / Android 16 / arm64 /
  4 KB pages; two additional scripted PTY byte round-trips also pass. The
  report identifies APK SHA-256
  `6637e095c518af0f678626c23dd194a2a2d5ff133d90093eb4b391382eb5bfab`.
  The PTY test verifies the expected byte sequence with Enter translated
  from CR to LF by ICRNL, but does not exercise a physical keyboard or UI.
  ELF load alignment is recorded; 16 KB-page-device boot is out of scope for
  v1. The report is isolated pre-G0 feasibility evidence, not production or
  reference-device G0 acceptance.
- The owner-supplied 2026-10-26 S7 report
  (`tests/spikes/results/S7-soak-20261026-041939.md`) records 631,745 ms of
  harness-service survival, six service heartbeats, zero restarts, and one
  alarm heartbeat on an Infinix X6870 / Android 16 / arm64 / 4 KB pages. The
  child-sampling diagnostic recorded 31/62 expected samples (50% coverage)
  and two gaps above 25 seconds (maximum gap 241 seconds), while the child
  remained alive at manual stop. The owner classifies sparse samples as
  Android scheduling behavior, not child-process failure. The device was not
  continuously screen-off, and no real agent task ran. The raw JSON and
  digest were not supplied. This is diagnostic evidence only; S7 and LC-2
  remain pending.
