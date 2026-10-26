# Source offer (draft)

This release includes GPL/LGPL/MPL components. The linked Alpine
aports revisions contain the build recipes and patches; complete
corresponding source will be archived with each release or provided
on request from the project maintainer for at least three years from
the date of distribution. The offer is valid for anyone who received
the binary.

## Alpine packages

| Package | Version | License | Build recipe / source |
| --- | --- | --- | --- |
| ada-libs | 2.7.8-r0 | ( Apache-2.0 OR MIT ) AND MPL-2.0 | https://gitlab.alpinelinux.org/alpine/aports/-/tree/8310983997e289c56912406d7303bad1612a82c4/main/ada |
| alpine-baselayout | 3.6.5-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/66187892e05b03a41d08e9acabd19b7576a1c875/main/alpine-baselayout |
| alpine-baselayout-data | 3.6.5-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/66187892e05b03a41d08e9acabd19b7576a1c875/main/alpine-baselayout |
| apk-tools | 2.14.4-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/d435c805af8af4171438da3ec3429c094aac4c6e/main/apk-tools |
| busybox | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |
| busybox-binsh | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |
| ca-certificates | 20261020-r0 | MPL-2.0 AND MIT | https://gitlab.alpinelinux.org/alpine/aports/-/tree/d0a696da2932feaa483492b66c2bedeed8dd2a8e/main/ca-certificates |
| ca-certificates-bundle | 20240705-r0 | MPL-2.0 AND MIT | https://gitlab.alpinelinux.org/alpine/aports/-/tree/5d17fc7e5ae113319f201d781607ffe4bee95b61/main/ca-certificates |
| libgcc | 13.2.1_git20240309-r1 | GPL-2.0-or-later AND LGPL-2.1-or-later | https://gitlab.alpinelinux.org/alpine/aports/-/tree/3f9204e1a873a55c29fcfa8ba42ec8209b264472/main/gcc |
| libstdc++ | 13.2.1_git20240309-r1 | GPL-2.0-or-later AND LGPL-2.1-or-later | https://gitlab.alpinelinux.org/alpine/aports/-/tree/3f9204e1a873a55c29fcfa8ba42ec8209b264472/main/gcc |
| musl-utils | 1.2.5-r0 | MIT AND BSD-2-Clause AND GPL-2.0-or-later | https://gitlab.alpinelinux.org/alpine/aports/-/tree/4fe5bdbe47b100daa6380f81c4c8ea3f99b61362/main/musl |
| scanelf | 1.3.7-r2 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/e65a4f2d0470e70d862ef2b5c412ecf2cb9ad0a6/main/pax-utils |
| ssl_client | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |

## Android libraries

| Library | Version | License | Source archive | SHA-256 |
| --- | --- | --- | --- | --- |
| PRoot | 5.1.107.101 | GPL-2.0-or-later | https://github.com/termux/proot/archive/refs/tags/v5.1.107.101.tar.gz | 41e55685788202b864647f5969a52a60408d6a6452f265919adde819afb26ef4 |
| libtalloc | 2.5.0 | LGPL-3.0-or-later | https://download.samba.org/pub/talloc/talloc-2.5.0.tar.gz | 912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007 |

libandroid-shmem is BSD-3-Clause and is covered by the notices.

PRoot's ELF `NEEDED` entries include `libtalloc.so.2` and
`libandroid-shmem.so`, so talloc is dynamically linked. The pinned
talloc 2.5.0 `LICENSE` and source headers are LGPL-3.0-or-later;
the Termux deb's `copyright` symlink points to `GPL-3.0.txt`.
Use the upstream source as authoritative and review this packaging
discrepancy before release.

## Bun (LGPL components)

Bun statically links JavaScriptCore (LGPL-2.0-only) and tinycc
(LGPL-2.1-only). Bun publishes relinking instructions in its
LICENSE.md: https://github.com/oven-sh/bun/blob/main/LICENSE.md.
Bun's other bundled libraries are listed in NOTICES.md; review
their licenses before release.

The `opencode` ELF's `NEEDED` entries are `libstdc++.so.6`,
`libc.musl-aarch64.so.1` and `libgcc_s.so.1`; no shared
JavaScriptCore or tinycc is listed, and JavaScriptCore source paths
are embedded in the binary. This confirms static linkage of the
LGPL components. Review packet: [bun-lgpl-review.md](bun-lgpl-review.md).

Bun 1.3.14 source archive:
https://github.com/oven-sh/bun/archive/refs/tags/bun-v1.3.14.tar.gz
(commit 0d9b296af33f2b851fcbf4df3e9ec89751734ba4; sha256
112a5915992807f04b183854d360c2bf87ac7c1587fb5da3c560bdbb75b8c92e).

| LGPL component | License | Source archive / repository | Commit |
| --- | --- | --- | --- |
| JavaScriptCore | LGPL-2.0-only | https://github.com/oven-sh/WebKit.git | 5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b |
| tinycc | LGPL-2.1-only | https://github.com/oven-sh/tinycc/archive/12882eee073cfe5c7621bcfadf679e1372d4537b.tar.gz | 12882eee073cfe5c7621bcfadf679e1372d4537b |

- JavaScriptCore: GitHub source archive returns HTTP 422; use git clone/fetch and checkout this commit.

## OpenCode dependency closure

The production/optional lockfile closure contains no GPL/AGPL
packages. MPL/AFL dual-licensed packages are used under their
permissive options; CC-BY attribution is recorded in NOTICES.md.

Per-package Alpine copyright notices are recorded in
alpine-copyright.json and NOTICES.md.
