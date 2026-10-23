# Acceptance evidence

Status vocabulary:

- `pending`: not started or no admissible run.
- `partial-host`: host-side evidence only; does not satisfy a device or app requirement.
- `emulator`: measured on an emulator in CI.
- `measured`: measured on the required physical device tier.

Every row starts as `pending`. A row may move only when its evidence is
linked or appended; host spikes cannot promote device rows.

## Device matrix

| Requirement | Scope | Status | Evidence | Blocker |
| --- | --- | --- | --- | --- |
| S1 | Tier A | pending | [staged harness run](tests/spikes/results/S3-device-harness-20261024-staged.md): loader exec `opencode --version` exit 0 on one device | production app/runtime implementation and remaining tiers |
| S1 | Tier B | pending | [staged harness run](tests/spikes/results/S3-device-harness-20261024-staged.md) covers Tier A device only | production app/runtime implementation and remaining tiers |
| S1 | Tier C | pending | none | production app/runtime implementation and remaining tiers |
| S2 cold start | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 idle RSS | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 git status, 10k files | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S2 npm test | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S3 | app sandbox + native PTY | pending | [device run](tests/spikes/results/S3-device-harness-20261024.md) (packaging blocked) and [staged rerun](tests/spikes/results/S3-device-harness-20261024-staged.md): S1/S3 packaging, loader exec, guest shell and native PTY all pass on one device | production app wiring and G0 acceptance run required; harness is pre-G0 |
| S6 | OpenCode 2.0.x | pending | none | no 2.x distributable artifact |
| S7 | Tier A/B/C | pending | [first device run](tests/spikes/results/S3-device-harness-20261024.md) and [staged rerun](tests/spikes/results/S3-device-harness-20261024-staged.md): FGS start/stop lifecycle observed on one device | Tier A/B/C coverage and background-survival duration pending |
| S8 | Gboard + Samsung Keyboard | pending | user reports a passing keyboard test on an Infinix Note 50s 5G using “emojikeyboard”; earlier trace observed text entry/edit updates and `KEYCODE_DEL` (67). The keyboard app/IME identity and test coverage are unverified. The 2026-10-24 04:22 confirmation JSON contains only S1/S3/S7 probes and explicitly did not exercise S8. | named Gboard and Samsung Keyboard runs, verified keyboard identity, and extra-key/paste/select coverage |
| RT-1 | Tier A/B/C, offline | pending | none | installable app unavailable |
| RT-2 | Tier A/B/C startup | pending | none | runtime implementation unavailable |
| RT-5 | 16 KB-page device | pending | none | no 16 KB-page target available |
| LC-2 | Tier A/B/C, 30 min background | pending | none | app lifecycle implementation and device access |
| LC-5 | Tier A/B, 4 h soak | pending | none | runtime implementation and device access |
| LC-6 | Tier A/B thermal | pending | none | runtime implementation and device access |
| TERM-5 | Gboard + Samsung Keyboard | pending | none | native PTY implementation and device access |
| STR-5-live-device | Tier B, 500 drops | pending | host runs only | app/UI transport unavailable |

## Notes

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
  passing keyboard test on an Infinix Note 50s 5G using “emojikeyboard,”
  but did not provide a separate trace or the keyboard app/IME identity
  and test coverage. The 2026-10-24 04:22 confirmation JSON is an S1/S3/S7
  repeat run and explicitly says S8 was not exercised. Extra-key,
  paste/select, Gboard and Samsung Keyboard coverage remains outstanding.
- G0 remains open; no acceptance value here freezes D4 or D5.
