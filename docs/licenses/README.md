# Licensing and distribution compliance (S9)

Status: draft. Inventory, two CycloneDX SBOMs, notices and a
source-offer draft were generated on 2026-10-23. The checklist must be
complete before the first public release. It supports ADR 0001 (D1)
and SEC-5.

## Components in the shipped APK

| Component | Version | License | Source | Obligations |
| --- | --- | --- | --- | --- |
| OpenCode official ARM64 musl release | 1.19.3 | MIT | `anomalyco/opencode` release | Include copyright and permission notice; verify bundled dependency notices |
| OpenCode npm closure | 1.19.3 lockfile | permissive + CC-BY | npm registry | See `opencode-deps.*`; include CC-BY attribution |
| Bun runtime inside artifact | 1.3.14 | MIT | Bun | Include Bun LICENSE and SBOM |
| Bun bundled libraries/polyfills | bundled | mixed | Bun | Listed in `NOTICES.md`; verify presence at release |
| JavaScriptCore/WebKit (inside Bun) | bundled | LGPL-2.0-only | Bun/WebKit | LGPL relink/source-offer review; Bun LICENSE |
| tinycc (inside Bun) | bundled | LGPL-2.1-only | Bun/tinycc | LGPL relink/source-offer review; Bun LICENSE |
| musl loader/libs | 1.2.5 | MIT | musl | Include notice |
| PRoot | 5.1.107.100 | GPL-2.0-or-later | `termux/proot` | Ship license, source archive (pinned) or written offer |
| libtalloc | 2.5.0 | LGPL-3.0-or-later | Samba talloc | LGPL relink/source-offer review |
| libandroid-shmem | 0.7 | BSD-3-Clause | `termux/libandroid-shmem` | Include copyright and license |
| Alpine minirootfs | 3.20.3 | mixed | Alpine release | Per-package notices; source offer for GPL/LGPL/MPL packages |
| Alpine `libgcc` / `libstdc++` | 13.2.1_git20240309-r1 | GPL-2.0-or-later AND LGPL-2.1-or-later | Alpine v3.20 aarch64 packages | Include notices and corresponding GCC source offer |
| App clean-room code | — | Apache-2.0 (accepted) | this repo | Include `LICENSE`; publish source with releases |
| React/TypeScript/Vite/CodeMirror/xterm.js | not added | MIT/BSD/Apache-2.0 | npm | Bundle notices via license scan |

The runtime tarball contains no license files, so notices must come from
the upstream projects and the Alpine package metadata, not from the
artifact itself. Bun's LICENSE.md is included because Bun statically
links LGPL-2.0 JavaScriptCore and LGPL-2.1 tinycc, plus roughly 50
other libraries and polyfills listed upstream. The LGPL relink/source
offer is a release blocker until reviewed.

## Generated artifacts

- [components.json](components.json) — machine-readable inventory
  (runtime, Alpine packages, licenses, source URLs).
- [sbom.cdx.json](sbom.cdx.json) — CycloneDX 1.5 SBOM for the app,
  runtime, Bun libraries, Android libraries and Alpine rootfs.
- [opencode-deps.json](opencode-deps.json) — 849-package production
  dependency closure of `packages/opencode` (835 external packages,
  1,066 install paths).
- [opencode-deps.cdx.json](opencode-deps.cdx.json) — CycloneDX 1.5
  SBOM for that closure.
- [alpine-copyright.json](alpine-copyright.json) — per-package Alpine
  copyright notices and evidence links.
- [NOTICES.md](NOTICES.md) — human-readable third-party notices.
- [SOURCE-OFFER.md](SOURCE-OFFER.md) — draft GPL/LGPL/MPL source offer.
- [bun-lgpl-review.md](bun-lgpl-review.md) — Bun LGPL legal-review packet.
- [texts/](texts/) — license texts and `SHA256SUMS`.
- `texts/hjinlabs-netizen-opencode-android-MIT.txt` preserves the MIT notice
  for the response-size guard adaptation documented in `docs/UPSTREAM.md`;
  app license assets include project-specific text files from `texts/`.

Regenerate after any runtime, rootfs or dependency change:

```bash
python3 scripts/fetch-runtime.py --alpine --android-libs
python3 scripts/build-opencode-sbom.py   # network; caches npm metadata
python3 scripts/build-licenses.py
```

## Checklist

### Decision and scope

- [x] Owner signed D1 (channels) and the Apache-2.0 app license
      (2026-10-23).
- [ ] Decide whether the sideload build exposes
      `MANAGE_EXTERNAL_STORAGE`; keep the Play variant SAF-only.
- [ ] Record trademark permission or rename the app.
- [ ] Record the supported Android/ABI matrix (minSdk 31, arm64-v8a).

### Notices and source

- [x] Generate a CycloneDX SBOM for the runtime and rootfs.
- [x] Generate a CycloneDX SBOM for OpenCode's production dependency
      closure from the pinned `bun.lock`.
- [x] Pin Bun 1.3.14 source/commit and record the source archive hash;
      pin the WebKit/JavaScriptCore and tinycc commits used by Bun.
- [x] Generate an Alpine package license inventory from the installed
      DB and APKINDEX repo mapping, including source URLs.
- [x] Collect per-package Alpine copyright notices from pinned
      APKBUILDs and upstream sources.
- [x] Copy MIT, Apache-2.0, GPL-2.0 and other required texts into
      `docs/licenses/texts/`.
- [x] Preserve the MIT copyright and permission notice for the adapted
      response-size guard and package it with the app license assets.
- [x] Include CC-BY attribution and license texts for `caniuse-lite`
      and `spdx-exceptions`.
- [x] Add the license texts to an in-app "Open source licenses" screen.
      Verified `:app:syncLicenses` and `:app:mergeDebugAssets`
      package `NOTICES.md`, `SOURCE-OFFER.md`, the generic license texts, and
      project-specific license notices;
      merged assets are byte-identical to `docs/licenses` (2026-10-23).
- [ ] Publish the PRoot corresponding source (or a written offer) with
      each release.
- [ ] Publish source for GPL/LGPL/MPL Alpine packages, or a valid
      written offer (draft exists).
- [x] Include upstream OpenCode/Bun/musl notices (draft).
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

- [x] `docs/licenses/` contains the inventory, texts and a source-offer
      draft (release links still pending).
- [x] Confirmed PRoot dynamically links `libtalloc.so.2` and
      `libandroid-shmem.so` with `readelf -d`; talloc source is
      LGPL-3.0-or-later.
- [x] Confirmed the `opencode` ELF has no shared JSC/tinycc dependency
      and embeds JavaScriptCore source paths, consistent with Bun's
      static LGPL linkage.
- [ ] Release notes state the exact runtime version, hashes and license
      changes.
- [ ] SEC-5 result: no unfixed critical/high vulnerabilities.

## Source-offer template

The generated draft is [SOURCE-OFFER.md](SOURCE-OFFER.md). The final
release must replace placeholders with the release URL and contact:

> This release includes PRoot (GPL-2.0-or-later), libtalloc
> (LGPL-3.0-or-later), Bun's statically linked LGPL components, and
> Alpine packages under GPL/LGPL/MPL licenses. Corresponding source is
> available at
> `<repository/release URL>` and, on request, from `<contact>` for at
> least three years from the date of distribution. The offer is valid
> for anyone who received this binary.

## Bun LGPL review notes

See [bun-lgpl-review.md](bun-lgpl-review.md) for the legal-review packet:
linkage evidence, pinned Bun/WebKit/tinycc sources, obligation mapping
and the source/relink versus object-file options. The packet is an
engineering summary, not legal advice.

## Known release blockers

1. **Bun LGPL components.** JavaScriptCore (LGPL-2.0-only) and tinycc
   (LGPL-2.1-only) are statically linked into the Bun binary; the
   `opencode` ELF has no shared JSC/tinycc dependency. Bun 1.3.14,
   WebKit/JavaScriptCore and tinycc source commits are pinned, but legal
   review must decide whether Bun's source/relink steps satisfy the
   object-file/source-offer requirement. Bun's other bundled libraries
   are listed in `NOTICES.md`; their versions and presence still need
   release-time verification.
2. **OpenCode closure accuracy.** The SBOM follows `bun.lock` install
   paths and aggregates duplicate versions, but it is not a binary scan.
   Verify the bundled set and CC-BY notices at release.
3. **libtalloc LGPL.** PRoot dynamically links `libtalloc.so.2`; the
   pinned talloc 2.5.0 source is LGPL-3.0-or-later, while the Termux deb
   copyright symlink points to `GPL-3.0.txt`. Use upstream source as
   authoritative and review the packaging discrepancy before release.
4. **Alpine notice coverage.** Per-package notices are collected, but
   `alpine-baselayout`, `alpine-baselayout-data` and `alpine-keys` ship
   no separate copyright file; review this residual gap at release.
5. **UI dependencies.** React/Vite/CodeMirror/xterm.js are not added
   yet; regenerate the SBOM when they land.

## Open questions

1. Can the Alpine rootfs be rebuilt from source in CI, or is a
   Debian-based rootfs required for F-Droid main inclusion?
2. Does the upstream OpenCode project grant trademark permission for a
   third-party mobile client name?
3. Which entity holds the release signing key and the developer
   verification account?
4. Does distributing the Bun binary require shipping relinkable object
   files, or is Bun's published source/relink path sufficient?
5. Does the binary include every package in the lockfile closure? Verify
   at release and trim or annotate the SBOM accordingly.
