# Runtime profile matrix (provisional)

Status: decision support for D4. **Not frozen.** No profile may be
treated as the default in code until G0 records the decision. Host
measurements here are directional and are not admissible for S1/S2.

## Decision under evaluation

D4 starts with Profile A as the proposed default. Profile B becomes the
default only if A fails S1 or S3. Profile B is rejected as the default if
it is more than 2.5x slower than A on the S2 git-status or npm-test
workloads.

Acceptance uses the pinned official OpenCode 1.19.3 ARM64 musl runtime,
Alpine 3.20.3, PRoot 5.1.107.100 and the D3 packaging rules. The official
runtime archive SHA-256 is
`39c9ff1ea47fd59752613079f97bef40a2d14c30bc6bb5772a91d9d708e2d0b3`.
The official archive contains only the `opencode` executable. Alpine provides
the musl loader; pinned Alpine `libgcc` and `libstdc++` packages provide the
other ELF dependencies. Host/device evidence recorded against the former
community archive must be rerun against this official package before it is
used to evaluate D4.

PRoot was moved from 5.1.107.98 to 5.1.107.100 on 2026-10-24 because the
Termux pool no longer serves the .98 deb (HTTP 404). The .100 deb and
source archive are pinned in `runtime/manifest.json`; the .100 binary's
LOAD segments are 16 KB aligned. Host S1/S3 evidence recorded before this
change used .98 and must not be presented as .100 device evidence.

## Profiles

| Property | Profile A (proposed default) | Profile B |
| --- | --- | --- |
| Server process | OpenCode runs directly through the bundled musl loader | Whole OpenCode server runs inside PRoot/Alpine |
| Agent shell | Redirected to the Alpine/PRoot guest through a `SHELL` wrapper | Already inside the guest |
| Terminal | Native Android `PtyService` through PRoot; server PTY is not used | Same |
| Git | Guest git through PRoot | Guest git inside the same guest |
| Packaging | Loader, PRoot and helpers from `jniLibs`; no executable from writable storage | Same entry-point rule; guest binary/rootfs handling must still be proven |
| Main cost | Wrapper correctness and split-filesystem risk | PRoot ptrace overhead on every server syscall |
| Reference evidence | Host smoke only | Host smoke only; AndCode is a source-level B reference but not our evidence |

The server's `/pty` routes are not an option for either profile. The
pinned 1.19.3 `/pty` returns 500 because its bundled `bun-pty` is
glibc-linked; D10's native PTY is required independently of D4.

## Host observations (2026-10-23)

Single host sample, Termux on Android 14, arm64-v8a, 4 KB pages. These
numbers are not device baselines and must not be used to ratify S1/S2.

| Metric | Profile A | Profile B |
| --- | --- | --- |
| `/global/health` | pass | pass |
| Cold start to health | 6,266 ms | 3,281 ms |
| Mock prompt round trip | 4,958 ms | 3,638 ms |
| RSS sample | 320 MB before prompt | 492 MB after prompt |
| Prompt tokens | 26 | 26 |

The sample is noisy, the RSS timestamps differ, and Profile B paid PRoot
startup costs differently. No default follows from it. The full S2
procedure still needs device runs, a 10k-file `git status`, `npm test`
through the agent, and thermals/background behavior.

Separate host evidence:

- The pinned artifact runs through the musl loader and matches the
  manifest hash; all ELF LOAD segments are 64 KB aligned (S1 partial,
  host only).
- `SHELL` is honored by the agent's bash tool, and a PRoot guest wrapper
  can run the command in Alpine with `/root` and the guest PATH (S3
  partial, host only).
- When a password is set, the server rejects all routes without
  credentials and accepts Basic auth as `opencode:<secret>` (S5 pass,
  host). Without a password, every route is open.
- The SSE resync path passed 500 injected drops on the host, including
  an authenticated run (S4 pass, host; app/UI pending).

## Device matrix

Every row below is pending. A host result cannot promote a row.

| Tier | Required run | Status | Blocker |
| --- | --- | --- | --- |
| A | S1 A/B start, health, mock prompt; S2 cold/RSS/git/npm | pending | app runtime implementation and device access |
| B | S1 A/B start, health, mock prompt; S2 cold/RSS/git/npm | pending | app runtime implementation and device access |
| C | S1 start with deviations recorded; S2 as available | pending | app runtime implementation and device access |
| A or B, 16 KB pages | RT-5 boot and `execute_no_trans` scan | pending | 16 KB-page device and app packaging |
| App sandbox | `execute_no_trans` denials, jniLibs launch, native PTY | pending | app implementation and device access |

Device evidence must record Android version, page size, ABI, thermal
status, run count, and whether the server, loader and PRoot came from
`jniLibs` or a copied rootfs. The latter is not admissible for D3.

## Decision rules

1. **S1:** A must start on 100% of Tier A/B devices. Tier C deviations
   are documented. If A fails any Tier A/B device, B becomes the default.
2. **S3:** Compare `pwd`, `node -v` and `PATH` in the agent shell and the
   native PTY. They must be identical. If A cannot meet this, B becomes
   the default.
3. **S2:** B becomes the default only through a S1 or S3 failure. B is
   rejected as default if it is more than 2.5x slower than A on the
   10k-file git-status or npm-test workload.
4. **D3:** A profile that cannot launch its host entry point from
   native-library storage, or that produces `execute_no_trans` denials,
   fails regardless of speed.
5. **PTY:** Both profiles require the native Android PTY. A server `/pty`
   success is not evidence for either profile.

## Open questions

- Does the app-sandbox loader/PRoot path start cleanly on targetSdk 36
  without `execute_no_trans` denials?
- Does the `SHELL` wrapper survive the full app supervisor and OpenCode
  config path, not only the host spike?
- Can the guest binary and rootfs be packaged without violating D3, or
  does Profile B require a copy/bind model that needs its own review?
- Does native `forkpty` through PRoot give identical `stty size`, signals
  and cleanup under both profiles?
- Which profile has acceptable thermal and background behavior on Tier
  B/C after the server has been running for 30 minutes?

## Evidence index

- `tests/spikes/results/S1-host-smoke.md`
- `tests/spikes/results/S2-host-profiles.md`
- `tests/spikes/results/S3-shell.md`
- `tests/spikes/results/S4-sse.md`
- `tests/spikes/results/S5-endpoints-auth.md`
- `docs/evidence/RESULTS.md` (authoritative pending device matrix)
- `docs/audit/andcode-review.md` (Profile B reference only)
- `docs/spec.md` D3/D4/D10 and Section 7.1/7.5
