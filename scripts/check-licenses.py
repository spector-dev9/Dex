#!/usr/bin/env python3
"""Validate the committed license inventory and SBOM."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LICENSES = ROOT / "docs" / "licenses"
MANIFEST = ROOT / "runtime" / "manifest.json"


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    for name in ("components.json", "sbom.cdx.json", "NOTICES.md", "SOURCE-OFFER.md"):
        if not (LICENSES / name).is_file():
            fail(f"missing docs/licenses/{name}")

    inventory = json.loads((LICENSES / "components.json").read_text(encoding="utf-8"))
    sbom = json.loads((LICENSES / "sbom.cdx.json").read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    if inventory.get("schema") != 1:
        fail("unsupported inventory schema")
    if inventory["alpine"]["rootfsSha256"] != manifest["alpine"]["sha256"]:
        fail("inventory rootfs hash does not match the manifest")
    if not inventory["packages"]:
        fail("inventory has no Alpine packages")
    if len(inventory.get("androidLibs", [])) != 3:
        fail("inventory must pin PRoot, libtalloc and libandroid-shmem")
    if sum(inventory["licenseSummary"].values()) != len(inventory["packages"]):
        fail("license summary does not cover every package")
    for package in inventory["packages"]:
        if not package["sourceUrl"].startswith("https://"):
            fail(f"package has no https source URL: {package['name']}")

    if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.5":
        fail("SBOM is not CycloneDX 1.5")
    if not str(sbom.get("serialNumber", "")).startswith("urn:uuid:"):
        fail("SBOM has no UUID serial number")
    components = sbom.get("components") or []
    if not components:
        fail("SBOM has no components")
    apk = [item for item in components if item.get("purl", "").startswith("pkg:apk/alpine/")]
    if len(apk) != len(inventory["packages"]):
        fail("SBOM Alpine component count does not match the inventory")
    for item in components:
        if not item.get("name") or not item.get("version"):
            fail(f"SBOM component lacks name/version: {item}")
        if not item.get("licenses"):
            fail(f"SBOM component lacks a license: {item['name']}")
    names = {item["name"] for item in components}
    for required in (
        "opencode",
        "opencode-termux",
        "bun",
        "JavaScriptCore (WebKit)",
        "tinycc",
        "PRoot",
        "libtalloc",
        "libandroid-shmem",
        "boringssl",
        "brotli",
    ):
        if required not in names:
            fail(f"SBOM is missing {required}")

    texts = LICENSES / "texts"
    for text in (
        "MIT.txt",
        "Apache-2.0.txt",
        "GPL-2.0-only.txt",
        "GPL-3.0-only.txt",
        "LGPL-3.0-or-later.txt",
        "BSD-3-Clause.txt",
        "Bun-LICENSE.md",
    ):
        if not (texts / text).is_file():
            fail(f"missing license text: texts/{text}")

    rootfs = (
        ROOT
        / "runtime"
        / "artifacts"
        / f"alpine-minirootfs-{manifest['alpine']['version']}-aarch64.tar.gz"
    )
    if rootfs.is_file():
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "build-licenses.py"), "--check"],
            check=False,
        )
        if result.returncode != 0:
            fail("generated license files are stale")

    print(
        f"license artifacts ok: {len(inventory['packages'])} Alpine packages, "
        f"{len(components)} SBOM components"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
