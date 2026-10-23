# Licensing and distribution compliance (S9)

Status: draft. This checklist must be complete before the first public
release. It supports ADR 0001 (D1) and SEC-5.

## Components in the shipped APK

| Component | Version | License | Source | Obligations |
| --- | --- | --- | --- | --- |
| OpenCode | 1.19.3 | MIT | Upstream release / `anomalyco/opencode` | Include copyright and permission notice |
| Community Termux artifact | 1.19.3 | MIT | `C04-wq/opencode-termux` release | Include notice; confirm bundled dependency notices |
| Bun runtime inside artifact | upstream | MIT | Bun | Include notice; SBOM |
| musl loader/libs | artifact | MIT | musl | Include notice |
| PRoot | pinned build | GPL-2.0 | `proot-me/proot` | Ship license, complete corresponding source or written offer (3 years) |
| Alpine minirootfs | 3.20.3 | mixed | Alpine release | Per-package notices; source offer for GPL/LGPL/MPL packages |
| App clean-room code | — | Apache-2.0 (accepted) | this repo | Include `LICENSE`; publish source with releases |
| React/TypeScript/Vite/CodeMirror/xterm.js | pinned | MIT/BSD/Apache-2.0 | npm | Bundle notices via license scan |

The runtime tarball contains no license files, so notices must come from
the upstream projects and the Alpine package metadata, not from the
artifact itself.

## Checklist

### Decision and scope

- [x] Owner signed D1 (channels) and the Apache-2.0 app license
      (2026-10-23).
- [ ] Decide whether the sideload build exposes
      `MANAGE_EXTERNAL_STORAGE`; keep the Play variant SAF-only.
- [ ] Record trademark permission or rename the app.
- [ ] Record the supported Android/ABI matrix (minSdk 31, arm64-v8a).

### Notices and source

- [ ] Generate a CycloneDX SBOM for app dependencies and the rootfs.
- [ ] Generate an Alpine package license inventory from APKINDEX/
      `apk info -a` metadata, including package source URLs.
- [ ] Copy MIT, Apache-2.0, GPL-2.0 and other required texts into
      `docs/licenses/` and into an in-app "Open source licenses" screen.
- [ ] Publish the PRoot corresponding source (or a written offer) with
      each release.
- [ ] Publish source for GPL/LGPL/MPL Alpine packages, or a valid
      written offer.
- [ ] Include upstream OpenCode/Bun/musl notices.
- [ ] Publish release checksums and the SBOM alongside the APK.

### Build and release

- [ ] Release build is signed with a dedicated key held offline/CI.
- [ ] `debuggable=false`; only the launcher activity exported (SEC-3).
- [ ] Reproducible-build check passes (REL-1).
- [ ] Downgrade protection and signature verification pass (REL-2/3).
- [ ] No runtime download on first launch; runtime hash verified against
      the manifest (D5).
- [ ] CI runs license check, secret scan and `osv-scanner` (SEC-2/5).

### Store and platform policy

- [ ] Play variant (if any) targets API 36, uses SAF only, declares the
      foreground-service type, and passes the executable-code review.
- [ ] F-Droid main repo is not targeted until the app is FLOSS and the
      rootfs/toolchain sourcing is accepted.
- [ ] Self-hosted F-Droid repo metadata and signing key are documented.
- [ ] Developer verification: register the application ID/signing
      identity before the 2027 global rollout; document ADB/advanced
      flow for unregistered installs.
- [ ] Privacy policy and data-safety answers match actual behavior
      (no analytics, no telemetry by default).

### Evidence

- [ ] `docs/licenses/` contains the inventory, texts and source-offer
      links used for the release.
- [ ] Release notes state the exact runtime version, hashes and license
      changes.
- [ ] SEC-5 result: no unfixed critical/high vulnerabilities.

## Source-offer template

> This release includes PRoot (GPL-2.0) and Alpine packages under
> GPL/LGPL/MPL licenses. Corresponding source is available at
> `<repository/release URL>` and, on request, from `<contact>` for at
> least three years from the date of distribution. The offer is valid
> for anyone who received this binary.

## Open questions

1. Can the Alpine rootfs be rebuilt from source in CI, or is a
   Debian-based rootfs required for F-Droid main inclusion?
2. Does the upstream OpenCode project grant trademark permission for a
   third-party mobile client name?
3. Which entity holds the release signing key and the developer
   verification account?
