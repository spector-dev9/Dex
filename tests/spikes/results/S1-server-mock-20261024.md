# S1 Server and Mock-Provider Smoke

> Historical record. v1 G0 uses the Infinix X6870 only; references below to
> Tier A/B/C, 16 KB-page devices, Samsung Keyboard, 30-minute/two-device runs
> or 19/20 repeats are superseded by `docs/spec.md` and `docs/plan/phase-0.md`.

Status: **GREEN for the S1 harness probe, based on user-reported logs.**

Two runs completed authenticated server health and a deterministic mock-provider
prompt round trip:

| Run | Start time (Unix ms) | Authenticated health | Mock prompt | Provider requests |
| --- | ---: | --- | --- | ---: |
| 1 | 1792884306731 | HTTP 200, 5,374 ms | Passed | 1 |
| 2 | 1792884326794 | HTTP 200, 4,342 ms | Passed | 1 |

Both runs created a session, accepted the asynchronous prompt, observed the
mock response, and shut down the probe. This report records the S1 server/mock
probe only; it does not assert that the full S1/S3 device suite passed.

Device model, Android version, APK SHA-256, and the subsequent S3 outcomes were
not included in the supplied logs. Therefore this smoke result does not promote
the Tier A/B/C S1 matrix row or close G0. See `docs/evidence/RESULTS.md` for
acceptance status.