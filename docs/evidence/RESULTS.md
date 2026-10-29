# G0 evidence status

*Updated 2026-10-30. This index is not authoritative for acceptance; use
`docs/plan/phase-0.md` and the raw reports it links.*

## Current status

**G0 is pending.** S1-A and S3 have owner-accepted harness evidence. S1-B,
the S2 profile workloads, S6, S9, baselines, and the D4 freeze remain open.
S7/LC-2 and S8/TERM-5 were removed from the G0 gate by the owner on
2026-10-30 and are not acceptance blockers.

## Latest device harness report

- Path: `/sdcard/Download/OpenCodeG0/reports/spike-20261030-020724.json`
- Report SHA-256:
  `0bd275eb375132c7376d673f767d4824d816bac18fa7921983fcd66b2e214a53`
- APK SHA-256:
  `f63f14ad18b4ad8b013e863b247ffce78be04466e33f121c0bbbe30550e3753c`
- Device: Infinix X6870, Android 16/API 36, arm64-v8a, 4 KB pages
- Result: 38/38 probe records passed; `g0Status` remains `pending`
- The APK hash matches the CI harness artifact downloaded for this run.
- The report is not archived in the repository and does not close the
  remaining S1-B/S2/S6/S9, baseline, or D4 gates.

## Prior evidence

- S1 loader, authenticated health, and mock-provider prompt: accepted
  harness evidence (`docs/evidence/Device 1`).
- S3 guest shell and native-PTY environment comparison: accepted harness
  evidence (`docs/evidence/Device 1`).
- S4/S5 host-side SSE and endpoint checks pass; app/UI and device acceptance
  remain pending.
- Raw historical two-device reports and earlier diagnostic exports remain
  under `docs/evidence/`; they are feasibility evidence, not G0 acceptance.
