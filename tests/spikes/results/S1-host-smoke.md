# S1 — Host smoke (partial)

Date: 2026-10-23. Host: Termux on Android 14 (API 36), arm64-v8a, 4 KB pages.
**This is not the app sandbox.** Termux runs unconfined; targetSdk 36
exec restrictions and W^X are not exercised.

## Procedure

```bash
python3 scripts/fetch-runtime.py
python3 scripts/verify-runtime.py
# launch
LD_LIBRARY_PATH=runtime/artifacts/opencode-1.19.3 \
  runtime/artifacts/opencode-1.19.3/ld-musl-aarch64.so.1 \
  --library-path runtime/artifacts/opencode-1.19.3 \
  runtime/artifacts/opencode-1.19.3/opencode --version
```

## Results

| Check | Result |
| --- | --- |
| Artifact sha-256 | matches `cd0d8342…6748` |
| Binary size | 195,014,520 bytes |
| Required files | 6/6 present, non-empty |
| ELF LOAD alignment | 0x10000 (65,536 B) on all ELF files; minimum 65,536 B |
| `--version` | `1.19.3`, exit 0 |
| Cold start to `/global/health` | 4.56 s, 4.57 s, 5.57 s (three runs) |
| Health status | 200 (no password); 200 with Basic auth |

## What this proves

- The pinned artifact runs on this aarch64 Android device through the
  bundled musl loader.
- The artifact satisfies the 16 KB-page alignment requirement (RT-5,
  artifact side).
- The manifest hash and required-file checks work.

## What this does not prove

- App-sandbox execution: no `execve` from app data, SELinux
  `execute_no_trans` denials, or targetSdk 36 behavior.
- Tier A/B/C devices, cold-start thresholds, thermal or background
  behavior (RT-2, LC-*).
- 16 KB-page device boot (host page size is 4 KB).
- Profile B (PRoot) or Profile A shell redirection (S2/S3).
