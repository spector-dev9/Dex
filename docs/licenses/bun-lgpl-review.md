# Bun LGPL compliance review packet

Status: open release blocker. Prepared 2026-10-23 for legal/owner
review. This is an engineering summary, not legal advice.

## Question for review

The shipped `opencode` runtime contains Bun 1.3.14, which statically
links JavaScriptCore/WebKit (LGPL-2.0-only) and tinycc (LGPL-2.1-only).
Does distributing the APK with Bun's published source and relink
instructions, plus the pinned component sources and a written offer,
satisfy the LGPL relink/source obligations? If not, are object files or
another delivery mechanism required?

## Components and pins

| Component | Version / commit | License | Evidence |
| --- | --- | --- | --- |
| Bun | 1.3.14, `0d9b296af33f2b851fcbf4df3e9ec89751734ba4` | MIT | `runtime/manifest.json`; source archive sha256 `112a5915992807f04b183854d360c2bf87ac7c1587fb5da3c560bdbb75b8c92e` |
| JavaScriptCore/WebKit | `5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b` | LGPL-2.0-only | `oven-sh/WebKit`; JSC `COPYING.LIB` is GNU Library GPL v2 |
| tinycc | `12882eee073cfe5c7621bcfadf679e1372d4537b` | LGPL-2.1-only | `oven-sh/tinycc`; source archive sha256 `6b50485fcbbfa90a99c56e8e2b6a92014dcd34377d5edb23e1938dc9ec96f0aa` |
| OpenCode runtime | 1.19.3 | MIT | Official `opencode-linux-arm64-musl.tar.gz` release; manifest-pinned SHA-256 |

Bun's LICENSE identifies the WebKit/JavaScriptCore and tinycc licenses
and says Bun statically links them. WebKit also contains additional
Apple/BSD-style notices that need review when its source is packaged.

## Linkage evidence

The pinned `opencode` ELF reports only these `NEEDED` libraries:

```text
libstdc++.so.6
libc.musl-aarch64.so.1
libgcc_s.so.1
```

There is no shared `libjavascriptcore` or `libtcc` dependency. Strings
in the binary include `vendor/WebKit/Source/JavaScriptCore/...` source
paths, and the binary reports:

```text
Bun v1.3.14 (0d9b296a) Linux arm64
```

This confirms that JSC and tinycc are part of the executable rather than
separately replaceable shared libraries.

## Bun's stated relink path

Bun's pinned `LICENSE.md` (sha256
`2c6160ec8fb853f7e8f97d9b249e756c9b0ac44860a68b6bf4f1b0bcbc5c3741`)
states:

> If you statically link against an LGPL'd library, you must also
> provide your application in an object (not necessarily source)
> format, so that a user has the opportunity to modify the library and
> relink the application.

It then says users can relink Bun with changes using:

```text
git submodule update --init --recursive
make jsc
zig build
```

and points to the patched WebKit repository. The Bun 1.3.14 release
assets are binaries only; no object files are published.

WebKit's GitHub source archive endpoint currently returns HTTP 422
(`Content creation is blocked`). Source is available by git fetch at the
pinned commit; this was verified with:

```text
git init
git remote add origin https://github.com/oven-sh/WebKit.git
git fetch --depth=1 --filter=blob:none origin 5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b
```

## Current compliance artifacts

- `NOTICES.md` names Bun, JavaScriptCore/WebKit and tinycc, with licenses
  and source links.
- `texts/Bun-LICENSE.md`, `texts/LGPL-2.0-only.txt` and
  `texts/LGPL-2.1-only.txt` are present and hash-checked.
- `SOURCE-OFFER.md` lists the Bun, WebKit/JSC and tinycc sources and the
  draft written offer.
- `runtime/manifest.json` pins the Bun source archive and both LGPL
  component commits.
- The written offer still contains release-URL/contact placeholders.

## Obligation mapping (engineering view)

| LGPL requirement | Current status | Gap / question |
| --- | --- | --- |
| Prominent notice that the libraries are used and covered | `NOTICES.md` has component rows | Confirm final APK/UI notice |
| Copy of the LGPL texts | Both texts included | Include in release/UI |
| Complete source of the libraries, including modifications | Bun, WebKit/JSC and tinycc commits pinned | Need source archive with each release |
| Source/object of the work that uses the libraries so users can relink | Bun source and relink steps published; no object files | **Counsel decision** |
| Written offer valid at least three years | Draft exists | Replace placeholders before release |
| Installation information, if required | Not documented | **Counsel decision for APK** |
| No additional restrictions on modified library use | App terms do not add restrictions | Confirm store terms |

## Options

| Option | What it requires | Effort | Main risk |
| --- | --- | --- | --- |
| 1. Source/relink path | Publish Bun, WebKit/JSC and tinycc sources; rely on Bun's relink instructions | Medium | Does not provide object files; counsel may reject |
| 2. Obtain object files | Get relinkable objects for the exact Bun build from upstream/builder; publish them | Unknown | Objects may not exist or may not match the artifact |
| 3. Build Bun in CI | Rebuild Bun and publish object files with the binary | High | Build time, reproducibility, Android toolchain |
| 4. Replace Bun | Use a runtime with dynamic LGPL libraries | Very high | OpenCode runtime compatibility |

Engineering recommendation: do not release until counsel chooses between
options 1 and 2/3. Option 1 is the least-effort path and its inputs are
now pinned, but it does not obviously satisfy the object-file wording in
the license. If counsel requires object files, ask the Bun maintainers
and the community artifact builder whether they can provide the exact
build objects; otherwise option 3 is the fallback.

## Questions for counsel

1. Does Bun's source plus `make jsc`/`zig build` relink path satisfy
   LGPL-2.0/2.1, or must we distribute object files?
2. Is the compiled `opencode` binary the relevant "work that uses the
   Library", and does its OpenCode/MIT source satisfy the work-source
   requirement?
3. Are the pinned WebKit/JSC and tinycc commits, plus Bun's patches,
   "complete corresponding source" for the shipped binary?
4. Does an Android APK require installation information or a specific
   relink mechanism beyond the written offer?
5. Is a GitHub release URL plus a three-year written offer sufficient,
   or must source be bundled in the release?
6. Do WebKit's additional Apple/BSD notices require separate
   attribution beyond `NOTICES.md`?

## Decision log

- [ ] Counsel/owner selects option 1, 2 or 3.
- [ ] Source archive and written offer finalized with release URL/contact.
- [x] In-app notices screen includes Bun/JSC/tinycc.
- [ ] S9 blocker cleared; release checklist updated.

## References

- Bun 1.3.14 LICENSE: <https://github.com/oven-sh/bun/blob/bun-v1.3.14/LICENSE.md>
- Bun source: <https://github.com/oven-sh/bun/archive/refs/tags/bun-v1.3.14.tar.gz>
- Patched WebKit: <https://github.com/oven-sh/WebKit>
- JSC license: <https://github.com/oven-sh/WebKit/blob/5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b/Source/JavaScriptCore/COPYING.LIB>
- tinycc fork: <https://github.com/oven-sh/tinycc>
- LGPL-2.0-only: <texts/LGPL-2.0-only.txt>
- LGPL-2.1-only: <texts/LGPL-2.1-only.txt>
- Current notices: <NOTICES.md>
- Current source offer: <SOURCE-OFFER.md>
