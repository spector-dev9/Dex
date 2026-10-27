# G3 reference-device baseline

Captured: 2026-10-27T11:33Z via ADB over wireless debugging.
Raw output: [`device-baseline-raw.txt`](device-baseline-raw.txt).

| Property | Value |
| --- | --- |
| Model | Infinix X6870 (product `X6870-IN`) |
| Android | 16 / API 36 |
| ABI | arm64-v8a |
| Page size | 4096 B (4 KB) |
| SoC | mt6878 |
| RAM | 5,583,124 kB total |
| `/data` free | 81 GB of 105 GB |
| Device uptime | 4 days 9 h (load average 19.6 at capture) |
| App package | `io.github.spectordev9.opencode` (uid 10375) |
| App version | versionCode 1, versionName `unspecified`, targetSdk 36 |
| Installed APK SHA-256 | `6b4803f02b6dd92e71a7500246bb053a5a3e12a22a98f630cbd9dec30e10ff64` (debug build) |
| First install / update | 2026-10-27 15:55:10 (local) |

## Launch and idle measurements

| Measurement | Result | Method |
| --- | --- | --- |
| Cold UI launch | 1756 ms | `am start -W` after `am force-stop` |
| Warm/hot UI launch | 81 ms | `am start -W` with activity in task |
| App-shell idle CPU | 0.0 % average over 60 s | `top -b -d 1 -p <pid> -n 60` surface row |
| App-shell RSS | 213 MB | same `top` sample |
| Thermal status | 0 (`THERMAL_STATUS_NONE`); CPU 64.8 °C | `dumpsys thermalservice` during the idle sample |

Raw idle sample: [`idle-top-60s-raw.txt`](idle-top-60s-raw.txt).

## Runtime state

The app launches but reports **RUNTIME OFFLINE**; it opens no TCP listener.
Screenshot: [`runtime-offline.png`](runtime-offline.png). This is expected while
`AndroidRuntimeProcessLauncher` remains the `E_RUNTIME_DOWN` stub and the UI is
not wired to the runtime manager, so device-side RT-1/RT-2/RT-3/STR/TERM/LC
criteria cannot be exercised through the app yet.

## Security spot checks

- Logcat scan during launch found **no** `execute_no_trans` (or other execute)
  denials whose `app=` field is the OpenCode package. Denials seen in the same
  window belong to Termux/`adb`.
- No `sk-…`, `OPENCODE_SERVER_PASSWORD`, `Bearer …` or `api_key=…` patterns were
  found by a byte scan of the installed APK.
- The installed APK is a **debug** build (`DEBUGGABLE`), so release-manifest
  invariants are enforced against the CI release manifest instead
  (`scripts/check-release-manifest.py`).
