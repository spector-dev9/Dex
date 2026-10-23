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
| S1 | Tier A | pending | none | app/runtime implementation and device access |
| S1 | Tier B | pending | none | app/runtime implementation and device access |
| S1 | Tier C | pending | none | app/runtime implementation and device access |
| S2 cold start | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 idle RSS | Profile A/B | pending | none | no app implementation; host spike is not admissible |
| S2 git status, 10k files | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S2 npm test | Profile A/B | pending | none | no app implementation; Profile B device evidence absent |
| S3 | app sandbox + native PTY | pending | host guest result; isolated harness builds with native PTY probe | harness has not been run on a device |
| S6 | OpenCode 2.0.x | pending | none | no 2.x distributable artifact |
| S7 | Tier A/B/C | pending | none | foreground service and device access |
| S8 | Gboard + Samsung Keyboard | pending | none | device and keyboard access |
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
- G0 remains open; no acceptance value here freezes D4 or D5.
