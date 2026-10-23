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
    for name in (
        "components.json",
        "sbom.cdx.json",
        "opencode-deps.json",
        "opencode-deps.cdx.json",
        "alpine-copyright.json",
        "NOTICES.md",
        "SOURCE-OFFER.md",
    ):
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

    alpine_copyright = json.loads(
        (LICENSES / "alpine-copyright.json").read_text(encoding="utf-8")
    )
    if alpine_copyright.get("schema") != 1:
        fail("unsupported Alpine copyright schema")
    if alpine_copyright["alpineVersion"] != manifest["alpine"]["version"]:
        fail("Alpine copyright version mismatch")
    alpine_by_name = {item["name"]: item for item in inventory["packages"]}
    if len(alpine_copyright["packages"]) != len(alpine_by_name):
        fail("Alpine copyright package count mismatch")
    if len({item["name"] for item in alpine_copyright["packages"]}) != len(alpine_by_name):
        fail("Alpine copyright package names are not unique")
    for item in alpine_copyright["packages"]:
        source = alpine_by_name.get(item["name"])
        if source is None:
            fail(f"Alpine copyright entry for unknown package: {item['name']}")
        if item["license"] != source["license"]:
            fail(f"Alpine copyright license mismatch: {item['name']}")
        if not item["copyright"] or not item["evidence"]:
            fail(f"Alpine copyright evidence missing: {item['name']}")
        for text in item["licenseTexts"]:
            if not (LICENSES / text).is_file():
                fail(f"Alpine copyright license text missing: {text}")

    deps = json.loads((LICENSES / "opencode-deps.json").read_text(encoding="utf-8"))
    deps_sbom = json.loads((LICENSES / "opencode-deps.cdx.json").read_text(encoding="utf-8"))
    if deps.get("schema") != 1:
        fail("unsupported OpenCode dependency schema")
    if deps["source"]["sha256"] != manifest["source"]["sha256"]:
        fail("OpenCode dependency SBOM source hash does not match the manifest")
    if deps["packageCount"] != len(deps["packages"]):
        fail("OpenCode dependency package count mismatch")
    if deps["instanceCount"] != sum(len(item["installPaths"]) for item in deps["packages"]):
        fail("OpenCode dependency install-path count mismatch")
    if deps["workspaceCount"] != sum(1 for item in deps["packages"] if item["workspace"]):
        fail("OpenCode dependency workspace count mismatch")
    if deps["externalCount"] != sum(1 for item in deps["packages"] if not item["workspace"]):
        fail("OpenCode dependency external count mismatch")
    if len({item["purl"] for item in deps["packages"]}) != deps["packageCount"]:
        fail("OpenCode dependency purls are not unique")
    if sum(deps["licenseSummary"].values()) != deps["packageCount"]:
        fail("OpenCode dependency license summary does not cover every package")
    if deps["unresolvedLicenses"]:
        fail("OpenCode dependency SBOM has unresolved licenses")
    if deps["unresolvedPackages"]:
        fail("OpenCode dependency SBOM has unresolved packages")
    if len(deps_sbom.get("components") or []) != deps["packageCount"]:
        fail("OpenCode dependency SBOM component count mismatch")
    if len({item["bom-ref"] for item in deps_sbom["components"]}) != deps["packageCount"]:
        fail("OpenCode dependency SBOM bom-refs are not unique")
    if deps_sbom["metadata"]["component"]["version"] != manifest["version"]:
        fail("OpenCode dependency SBOM version mismatch")
    deps_names = {item["name"] for item in deps["packages"]}
    for required in ("caniuse-lite", "spdx-exceptions", "dompurify", "json-schema", "type-fest"):
        if required not in deps_names:
            fail(f"OpenCode dependency SBOM is missing {required}")
    for item in deps["packages"]:
        if "GPL" in item["license"] or "AGPL" in item["license"]:
            fail(f"unreviewed copyleft dependency: {item['name']} ({item['license']})")

    texts = LICENSES / "texts"
    for text in (
        "MIT.txt",
        "Apache-2.0.txt",
        "GPL-2.0-only.txt",
        "GPL-3.0-only.txt",
        "LGPL-3.0-or-later.txt",
        "BSD-3-Clause.txt",
        "CC-BY-3.0.txt",
        "CC-BY-4.0.txt",
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
        f"{len(components)} main SBOM components, {deps['packageCount']} "
        f"OpenCode dependency packages"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
