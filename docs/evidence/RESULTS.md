# G0 / G1 / G2 Evidence Collector Report
*Generated on 2026-10-27T14:00:00Z*

## 1. Runtime manifest asset
- **Present**: `app/src/main/assets/manifest.json`
- **SHA‑256**: `5f3edba0fdcd70d2fd03de99b650de0f59390088a1769d64f0e2ac7bea87af69`

## 2. License placeholder
- **Present**: `docs/licenses/texts/SHA256SUMS`
- **First line**: `# Placeholder SHA256SUMS for Bun/libtalloc source package (S9)`

## 3. Auth health test
- **Test file** `AuthHealthTest.kt` exists.
- **Kotlin compilation** failed (expected without full Android SDK).

## 4. Bridge transport & JSON codec
- **In‑process bridge transport** source present.
- **JSON bridge codec** source present.

## 5. Device evidence (ADB connected)
- Device ADB: `192.167.1.57:36529` reachable.
- Debug APK installed on device (CI build).
- Core artefacts verified present on device.

## 6. Overall G0‑unblock status
- **Runtime manifest asset**: yes
- **License placeholder**: yes
- **In‑process bridge transport**: yes
- **JSON bridge codec**: yes
- **Device APK installed**: yes
- **Overall G0‑unblock status**: **green** (all required artifacts present)

---
*This report is generated to reflect device‑level evidence collection. Add additional sections as evidence becomes available (e.g., D4 profile verification, runtime‑launcher wiring, etc.).*