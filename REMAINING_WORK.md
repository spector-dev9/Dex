# Remaining Work

Updated: 2026-10-27.

G0–G2 are green in CI. The live G3 acceptance tracker is
[`docs/evidence/g3/G3_STATUS.md`](docs/evidence/g3/G3_STATUS.md) with a
machine-readable copy in `docs/evidence/g3/status.json`; CI validates that every
section-7 criterion is present and cited (`scripts/check-g3-status.py`).

## Completed

| Item | Status |
| --- | --- |
| `binarySha256` in the app asset manifest | Fixed: valid JSON and the real pinned hash `37c7de55…9ecd`, checked against `runtime/manifest.json` in CI |
| `AndroidRuntimeProcessLauncher` compile errors | Fixed: minimal `E_RUNTIME_DOWN` stub; the Android build passes |
| CI pipeline | Green for contract, Kotlin and Android (run `40495019508`) |
| Device-level evidence | `docs/evidence/g3/` with baseline, runtime probe, host tests and idle sample |
| License/SBOM artefacts | Green (`scripts/check-licenses.py`) |
| Temporary marker files | Removed |

## Open G3 work (grouped, see tracker for per-ID status)

1. **Runtime wiring** — install the pinned runtime, implement a real
   `AndroidRuntimeProcessLauncher`, start the supervised server, and connect the
   UI. Unblocks RT-1/2/3/4, STR-1..5, TERM-1..5, LC-1/2/4, SEC-4.
2. **Git service** — implement `core/git-api` (WS-6).
3. **Release engineering** — signing config, two-build diffoscope comparison,
   upgrade/install docs (REL-1/2/3).
4. **Supply chain** — osv-scanner and the GPL source offer (SEC-5).
5. **Remaining G3 runs** — finish WS-2 to 1,000 runs, LC-3 50 SIGKILL runs,
   live 500-drop STR-5, and the app-sandbox variants of the section-7 scripts.

The only explicitly deferred items are LC-5 (4 h memory soak) and LC-6 (30 min
thermal soak), allowed by the G3 gate as post-v1 optional checks.
