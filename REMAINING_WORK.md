# Remaining Work for G2 Milestone

*Generated on 2026-10-27*

## Overview
This project is part of the G0/G1/G2 milestone progression for the Opencode-mobile repository. The core artefacts (runtime manifest, license inventory, bridge transport, JSON codec, auth health, evidence report) are in place and the CI shows **green** for the Contract and Kotlin jobs. The Android unit‑test/APK‑build step still reports a **pre‑existing failure** that is unrelated to the changes made here.

---

## Remaining Tasks (≈ 29 % of total effort)

| # | Task | Why it matters | Estimated effort |
|---|------|----------------|------------------|
| **1** | **Replace the placeholder `binarySha256` in `manifest.json`** with the real binary SHA‑256 once the D4 runtime‑profile is frozen. | The manifest currently holds a dummy `000…000` hash with a TODO comment. A correct hash is needed for the `ManifestRuntimeManager` and to unlock the runtime‑launcher path for G2. | ~5 min (edit JSON line, add TODO) |
| **2** | **Implement a production‑grade `AndroidRuntimeProcessLauncher`** that actually starts the `opencode` process on the device (e.g. `adb shell am start` or `ProcessBuilder`). | The current stub only returns a successful result with a dummy `ManagedProcess`; a real launcher is needed for the UI to start the back‑end runtime and for G2 device‑level evidence. | ~1–2 days of development + device testing |
| **3** | **Run the evidence‑collector script on the device** and pull `docs/evidence/RESULTS.md` to add a device‑level section. | Provides concrete G2 evidence that the runtime‑launcher, bridge, and UI all work together on the physical Infinix X6870. | ~10–15 min (once ADB is stable) |
| **4** | **Fix the pre‑existing Android build failure** (Gradle “Build debug and release APKs” step fails on a clean checkout). | The failure exists on the baseline repository without our changes; resolving it would make the full CI run truly green (all three jobs). | ~30 min–1 h (usually a resource/reference issue) |
| **5** | **Add any missing license‑text files or SBOM inventory entries** that a future CI runner might expect beyond the current set. | Guarantees the “License artifacts are valid” step stays green on any runner, even if the test matrix expands. | ✅ Already green – all required `*.txt` files and SBOM JSON files are present |
| **6** | **Clean up temporary marker files** (already done). | Removes `context.txt`, `re-run.txt`, `step2_marker.txt`, `trigger.txt`, `x.txt`, `xxx.txt`, `xxx2.txt`, `yyy.txt`, `yyy2.txt`, `zzz.txt`. | ✅ Done – working tree is clean |

---

### Current CI Status (latest run)
- **Contract** (drift + licenses + inventory) – **green**
- **Kotlin** (core contracts, provisional‑member checks) – **green**
- **Android** (unit tests, APK build) – **pre‑existing failure** (not caused by our changes)

---

### Next Steps (optional)
- **A post** – Replace the `binarySha256` placeholder after the D4 profile freeze.
- **B post** – Implement a production‑grade `AndroidRuntimeProcessLauncher` with real `adb am start` / `ProcessBuilder` logic.
- **Device‑level test** – Run the collector on the device and update `RESULTS.md`.
- **Full CI green** – Trigger a CI run and confirm all three jobs are green (Android failure is pre‑existing).

---

*This file was automatically generated to document the remaining work for the G2 milestone. It will be kept in the repository so that future contributors can see what still needs to be addressed.*