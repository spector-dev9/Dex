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
| S1 | Tier A | pending | [staged harness run](tests/spikes/results/S3-device-harness-20261024-staged.md), [official-package rerun](tests/spikes/results/raw/spike-20261024-080149.json), and [runtime-download rerun](spike-20261024-153116.json): loader exec `opencode --version` exit 0 on one Infinix device | full server/prompt path, production app/runtime implementation and remaining tiers |
| S1 | Tier B | pending | [staged harness run](tests/spikes/results/S3-device-harness-20261024-staged.md) covers Tier A device only | production app/runtime implementation and remaining tiers |
| S1 | Tier C | pending | none | production app/runtime implementation and remaining tiers |
| S2 cold start | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 idle RSS | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 git status, 10k files | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S2 npm test | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S3 | app sandbox + native PTY | pending | [device run](tests/spikes/results/S3-device-harness-20261024.md) (packaging blocked), [staged rerun](tests/spikes/results/S3-device-harness-20261024-staged.md), [official-package rerun](tests/spikes/results/raw/spike-20261024-080149.json), and [runtime-download rerun](spike-20261024-153116.json): guest shell and native PTY pass on one device | production app wiring and G0 acceptance run required; harness is pre-G0 |
| S6 | OpenCode 2.0.x | pending | none | no 2.x distributable artifact |
| S7 | Tier A/B/C | pending | [first device run](tests/spikes/results/S3-device-harness-20261024.md), [staged rerun](tests/spikes/results/S3-device-harness-20261024-staged.md), [official-package rerun](tests/spikes/results/raw/spike-20261024-080149.json), and [runtime-download rerun](spike-20261024-153116.json): FGS start/stop lifecycle observed on one device | Tier A/B/C coverage and background-survival duration pending |
| S8 | Gboard + Samsung Keyboard | pending | [runtime-download rerun](spike-20261024-153116.json) records 7 text changes, final length 6, and zero key-down events. Owner-approved earlier “emojikeyboard” result remains unverified for IME identity. | named Gboard and Samsung Keyboard runs, observed extra-key events, paste/select coverage |
| RT-1 | Tier A/B/C, offline | pending | none | installable app unavailable |
| RT-2 | Tier A/B/C startup | pending | none | runtime implementation unavailable |
| RT-5 | 16 KB-page device | pending | none | no 16 KB-page target available |
| LC-2 | Tier A/B/C, 30 min background | pending | none | app lifecycle implementation and device access |
| LC-5 | Tier A/B, 4 h soak | pending | none | runtime implementation and device access |
| LC-6 | Tier A/B thermal | pending | none | runtime implementation and device access |
| TERM-5 | Gboard + Samsung Keyboard | pending | none | native PTY implementation and device access |
| STR-5-live-device | Tier B, 500 drops | pending | host runs only | app/UI transport unavailable |

## Notes

- The earlier physical-device runs predate the current official
  OpenCode package and remain historical evidence. The new
  `spike-20261024-080149.json` report records OpenCode `1.19.3`,
  Alpine `3.20.3`, successful loader/guest-shell/native-PTY and
  foreground-service start/stop probes on an Infinix X6870 running
  Android 16 with 4 KB pages. The report does not include the staged
  archive SHA-256, so it does not independently prove byte-for-byte
  correspondence with the pinned artifact.
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
  rebuilt post-staging APK passes every S1/S3 probe — loader runs
  `opencode --version` (exit 0, stdout `1.19.3`), PRoot guest shell and
  the native `forkpty` comparison both pass, and the S7 service
  lifecycle completes. The initial side-load again showed the whole
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
  (`raw/spike-device-20261024-042222.json`) passed all 13 harness records
  again, confirming the result is deterministic.
- The official-package rerun is a separate report at
  `raw/spike-20261024-080149.json`. It passes the staged runtime layout,
  OpenCode loader version, PRoot guest shell, native PTY comparison, and
  foreground-service start/stop. It records five S8 text changes but no
  key-down events. This remains partial harness evidence: S1 server and
  prompt behavior, long-duration service survival, tier coverage, and
  full S8 acceptance are still outstanding.
- The runtime-download rerun at `spike-20261024-153116.json` records
  15/15 passing results on the same Infinix X6870 / Android 16 device.
  The pinned OpenCode, Alpine rootfs, `libgcc`, and `libstdc++` archive
  hashes match the manifest; the download/install action took 39.05 s.
  The preceding attempt had already completed and cached these archives
  before Alpine APK extraction failed on PAX metadata, so this rerun is
  not a cold-network speed measurement. Loader, guest-shell, PTY and FGS
  probes pass again. S8 records seven text changes and zero key-down
  events. This remains pre-G0 evidence on a 4 KB-page device; it does not
  satisfy the device matrix, server/prompt round trip, long-duration FGS
  test, or named keyboard acceptance.
- G0 remains open; no acceptance value here freezes D4 or D5.
