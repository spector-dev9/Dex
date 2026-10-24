# Acceptance evidence

Status vocabulary:

- `pending`: not started or no admissible run.
- `partial-host`: host-side evidence only; does not satisfy a device or app requirement.
- `emulator`: measured on an emulator in CI.
- `measured`: measured on the required physical device tier.

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

## Device matrix

| Requirement | Scope | Status | Evidence | Blocker |
| --- | --- | --- | --- | --- |
| S1 | Tier A | pending | Two physical harness summaries ([Device 1](Device%201), [Device 2](Test%202)) each report OpenCode 1.19.3 loader, authenticated health, and two complete mock-provider prompt round trips. Both used the same APK SHA-256 `a5e0deb3ab7e6bf61983803a3a8ea3908d3aa064186d449f286d26e7105980b6`. | production app/runtime implementation and required tier-specific device matrix |
| S1 | Tier B | pending | The two physical runs are Android 16 arm64 devices, but supplied memory/OS data do not establish the specified Tier B profile (6 GB, Android 13/14). See [Device 1](Device%201) and [Device 2](Test%202). | test on representative Tier B device and production app/runtime implementation |
| S1 | Tier C | pending | none | production app/runtime implementation and remaining tiers |
| S2 cold start | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 idle RSS | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 git status, 10k files | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S2 npm test | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S3 | app sandbox + native PTY | pending | Both [Device 1](Device%201) and [Device 2](Test%202) summaries report `N=v20.15.1`, `GUEST-OK`, and matching native-PTY environment values on Android 16 arm64 devices with 4 KB pages. | production agent-shell wiring, comparison against actual agent bash, representative device tiers, and a 16 KB-page device; isolated harness results are feasibility only |
| S6 | OpenCode 2.0.x | pending | none | no 2.x distributable artifact |
| S7 | Tier A/B/C | pending | The exploratory harness soak failed its 30-minute minimum on both devices: [Device 1](Device%201) ran 782,647 ms (~13 min); [Device 2](Test%202) ran 25,237 ms (~25 sec). Both reported a fresh heartbeat at export; screen-off was not measured. | investigate failures; run with screen off and an agent task; 19/20 repeated runs and Tier A/B/C coverage |
| S8 | Gboard + Samsung Keyboard | pending | Both device summaries identify Gboard, record text changes, and report zero key-down events; keyboard acceptance is not assessed. See [Device 1](Device%201) and [Device 2](Test%202). | Samsung Keyboard and named Gboard acceptance runs, extra-key events, paste/select coverage |
| RT-1 | Tier A/B/C, offline | pending | none | installable app unavailable |
| RT-2 | Tier A/B/C startup | pending | none | runtime implementation unavailable |
| RT-5 | 16 KB-page device | pending | none | no 16 KB-page target available |
| LC-2 | Tier A/B/C, 30 min background | pending | Two exploratory harness runs did not reach the 30-minute duration: ~13 min on Device 1 and ~25 sec on Device 2. Neither measures a production agent task or verified screen-off interval. | diagnose service/process interruptions; agent-task survival; 19/20 repeated runs and device-tier coverage |
| LC-5 | Tier A/B, 4 h soak | pending | none | runtime implementation and device access |
| LC-6 | Tier A/B thermal | pending | none | runtime implementation and device access |
| TERM-5 | Gboard + Samsung Keyboard | pending | none | native PTY implementation and device access |
| STR-5-live-device | Tier B, 500 drops | pending | host runs only | app/UI transport unavailable |

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
- The new S8 result confirms text changes were recorded but reports
  `keyDownEvents=0`; it does not demonstrate hardware-key events,
  keyboard identity, paste/select behavior, or named Gboard/Samsung
  coverage. The `16KB-ok` strings describe native ELF load alignment;
  this device reports 4096-byte pages and is not 16 KB-device evidence.
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
  explicitly says S8 was not exercised. Extra-key, paste/select, Gboard
  and Samsung Keyboard coverage remains outstanding.
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
  prompt behavior, long-duration service survival, tier coverage, and
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
  events. This remains pre-G0 evidence on a 4 KB-page device; it does not
  satisfy the device matrix, long-duration FGS test, or named keyboard
  acceptance. The separate S1 diagnostics prove one mock prompt round
  trip, but not the required tier/device matrix.
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
  does not close the full device matrix or G0.
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
  Device 2's at 25,237 ms (~25 seconds), both below 1,800,000 ms. They
  therefore fail the harness's 30-minute exploratory criterion. The
  reports state `screenOff=not-measured`; this is not LC-2 acceptance.
  Both S8 records identify Gboard, show text changes but zero key-down
  events, and mark keyboard acceptance not assessed. These results add
  two-device feasibility data only; neither device is a 16 KB-page target,
  and the runs do not establish the required tier matrix or G0.
- G0 remains open; no acceptance value here freezes D4 or D5.
