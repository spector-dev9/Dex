# S8 Hacker's Keyboard field observation — 2026-10-26

Status: **owner-supplied field observation only; `keyboardAcceptance=not-assessed`.**

Raw report: [`docs/evidence/spike-20261026-071830.json`](../../../docs/evidence/spike-20261026-071830.json)

- Device: Infinix X6870, Android 16 (SDK 36), arm64-v8a, 4 KB pages
- IME: Hacker's Keyboard (`org.pocketworkstation.pckeyboard/.LatinIME`)
- APK SHA-256: `6637e095c518af0f678626c23dd194a2a2d5ff133d90093eb4b391382eb5bfab`

| Observation | Result | Counters |
| --- | --- | --- |
| 1 | `pass=true` | 38 text changes; 41 hardware key-downs; 80 IME key events; 27 commits; 42 selection changes; last event was Enter |
| 2 | `pass=true` | 77 text changes; 97 hardware key-downs; 194 IME key events; 23 commits; 88 selection changes; 1 context-menu event; last event was Tab |

Both records omit input text and leave `keyboardAcceptance=not-assessed`. The
report contains counters and only the last event for each observation, so it
does not establish Ctrl/Fn key mappings or the bytes those keys produce in a
PTY. It is not a PTY-backed TERM-5 result and does not test the production
terminal UI.
