# S8 IME Observation

> Historical record. v1 G0 uses the Infinix X6870 only; references below to
> Tier A/B/C, 16 KB-page devices, Samsung Keyboard, 30-minute/two-device runs
> or 19/20 repeats are superseded by `docs/spec.md` and `docs/plan/phase-0.md`.

Status: **GREEN for the EditText observation only; `keyboardAcceptance=not-assessed` is intentional.**

Reported at `2026-10-25T05:05:04+05:30` on Infinix X6870, Android 16
(SDK 36), arm64-v8a, 4096-byte pages. APK SHA-256
`99770d872a2b889c8d2ca5b79bb0b2df14d46d7c392457efff1fdc616c1d08c4`
matches the rebuilt staged harness APK.

This probe only observes an `EditText` via `InputConnection`/`TextWatcher`.
It does not test a PTY-backed terminal surface or the TERM-5 requirements:
Esc, Tab, Ctrl, Alt, arrow keys, exact byte-to-PTY mapping, and both Gboard
+ Samsung Keyboard. The observation therefore remains `not-assessed`, not a
probe failure.

| IME | Observation | Result |
| --- | --- | --- |
| Facemoji (`com.facemoji.lite.transsion/com.baidu.simeji.SimejiIME`) | 3 text changes, 3 composing-text events, 4 selection changes | Captured |
| Gboard (`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`) | One record: 1 text change, final text length 1305, 1 context-menu event; another: 5 text changes, 5 `commitText` events, 14 selection changes | Captured |

All three harness records passed because input was observed. Hardware key-down
and IME key-event counts were zero; this is not a failure for soft keyboards.
The reports explicitly say `keyboardAcceptance=not-assessed` and omit typed
input text.

## Follow-up Gboard run

A second user-supplied report at `2026-10-25T05:16:17+05:30` identifies the
same Infinix X6870 and APK SHA-256. Its Gboard observation has `pass=true` and
records 192 text changes, 169 `commitText` events, 2 composing-text events, 48
IME key events, 24 hardware key-downs, 204 selection changes, and 4 context
menu events. Deletion, editor-action, and content-commit counters are zero.
The input text is omitted.

The report says `keyboardAcceptance=assessed`, but that label conflicts with
the matching APK: inspection of its DEX shows the hardcoded
`keyboardAcceptance=not-assessed` string, which is also emitted by the current
source. The owner reports the Gboard test as green; preserve the mismatch as a
provenance issue. This field-input result does not establish native-terminal
TERM-5 or G0/device-tier acceptance.