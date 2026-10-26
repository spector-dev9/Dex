# S2 — Profile A/B host smoke

> Historical record. v1 G0 uses the Infinix X6870 only; references below to
> Tier A/B/C, 16 KB-page devices, Samsung Keyboard, 30-minute/two-device runs
> or 19/20 repeats are superseded by `docs/spec.md` and `docs/plan/phase-0.md`.

Date: 2026-10-23. Status: **partial (host only)** — both profiles
start and complete a mock-provider prompt round trip. Device,
git/npm and thermal comparisons are still required.

> Pin note (2026-10-24): this host run used PRoot 5.1.107.98. The pin is
> now 5.1.107.100 because the .98 Termux deb returns HTTP 404. Re-run
> Profile B on devices with .100; do not present this result as .100
> evidence.

## Setup

- Pinned OpenCode 1.19.3 with Alpine 3.20.3 and PRoot 5.1.107.98.
- Profile A: run the loader and binary directly, with a mock provider.
- Profile B: run the same binary inside PRoot Alpine. The test copied
  the binary and libraries to `/opt/opencode` and created
  `/lib/ld-musl-aarch64.so.1` in the rootfs.
- Prompt: one no-tool request against the deterministic mock; 16
  streamed tokens.

## Observed (single host sample)

| Metric | Profile A | Profile B |
| --- | --- | --- |
| Health | pass | pass |
| Cold start to health | 6,266 ms | 3,281 ms |
| Prompt round trip | 4,958 ms | 3,638 ms |
| RSS observed | 320 MB (pre-prompt) | 492 MB (post-prompt) |
| Prompt tokens | 26 | 26 |

The host numbers are noisy and do not establish a default. Profile B
needs PRoot overhead, but its cold start was faster in this sample.
RSS after prompt was similar (A 515 MB, B 492 MB), and Profile B adds
a PRoot process (3.6 MB RSS). The pre/post-prompt samples are not
directly comparable.

## Conclusion

Both profiles are viable on the host. Profile A keeps the agent shell
redirection requirement from S3; Profile B naturally runs the agent,
server and PTY in one guest. The default stays undecided until the
device benchmark and the S2 2.5x rule are applied.

## Still required

- Tier A/B/C device runs: cold start, idle RSS, 10k-file git status,
  `npm test` through the agent, thermals/background behavior.
- Packaging test with the binary and loader in native library storage,
  not a copied rootfs convenience.
- Decide whether Profile B's copy/bind model fits the D3 W^X rule.

Raw evidence: `results/raw/s2-host-profiles.json`.
