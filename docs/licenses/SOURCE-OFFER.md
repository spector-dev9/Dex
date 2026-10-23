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
| alpine-baselayout | 3.6.5-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/66187892e05b03a41d08e9acabd19b7576a1c875/main/alpine-baselayout |
| alpine-baselayout-data | 3.6.5-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/66187892e05b03a41d08e9acabd19b7576a1c875/main/alpine-baselayout |
| apk-tools | 2.14.4-r0 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/d435c805af8af4171438da3ec3429c094aac4c6e/main/apk-tools |
| busybox | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |
| busybox-binsh | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |
| ca-certificates-bundle | 20240705-r0 | MPL-2.0 AND MIT | https://gitlab.alpinelinux.org/alpine/aports/-/tree/5d17fc7e5ae113319f201d781607ffe4bee95b61/main/ca-certificates |
| musl-utils | 1.2.5-r0 | MIT AND BSD-2-Clause AND GPL-2.0-or-later | https://gitlab.alpinelinux.org/alpine/aports/-/tree/4fe5bdbe47b100daa6380f81c4c8ea3f99b61362/main/musl |
| scanelf | 1.3.7-r2 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/e65a4f2d0470e70d862ef2b5c412ecf2cb9ad0a6/main/pax-utils |
| ssl_client | 1.36.1-r29 | GPL-2.0-only | https://gitlab.alpinelinux.org/alpine/aports/-/tree/1747c01fb96905f101c25609011589d28e01cbb8/main/busybox |

## Android libraries

| Library | Version | License | Source archive | SHA-256 |
| --- | --- | --- | --- | --- |
| PRoot | 5.1.107.98 | GPL-2.0-only | https://github.com/termux/proot/archive/refs/tags/v5.1.107.98.tar.gz | a1237d2039e0efedd1639aa4a99ac5e48a10f4c2e14cce445b2cbebedf32d22e |
| libtalloc | 2.5.0 | LGPL-3.0-or-later | https://download.samba.org/pub/talloc/talloc-2.5.0.tar.gz | 912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007 |

libandroid-shmem is BSD-3-Clause and is covered by the notices.

## Bun (LGPL components)

Bun statically links JavaScriptCore (LGPL-2.0-only) and tinycc
(LGPL-2.1-only). Bun publishes relinking instructions in its
LICENSE.md: https://github.com/oven-sh/bun/blob/main/LICENSE.md.
Bun's other bundled libraries are listed in NOTICES.md; review
their licenses before release.

## OpenCode dependency closure

The production/optional lockfile closure contains no GPL/AGPL
packages. MPL/AFL dual-licensed packages are used under their
permissive options; CC-BY attribution is recorded in NOTICES.md.

Per-package Alpine copyright notices are recorded in
alpine-copyright.json and NOTICES.md.
