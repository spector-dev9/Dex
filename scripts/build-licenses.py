#!/usr/bin/env python3
"""Generate the license inventory, CycloneDX SBOM and notices.

Inputs are the pinned manifest, the pinned Alpine minirootfs and the
Alpine APKINDEX used only to map package names to repositories. The
rootfs and indexes are downloaded into runtime/artifacts/ (gitignored).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tarfile
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = ROOT / "runtime" / "manifest.json"
DEFAULT_ARTIFACTS = ROOT / "runtime" / "artifacts"
DEFAULT_OUT = ROOT / "docs" / "licenses"
REPOS = ("main", "community")
ARCH = "aarch64"


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "opencode-mobile-s9"})
    print(f"downloading {url}")
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            output.write(chunk)
    os.replace(partial, destination)


def parse_stanzas(text: str) -> list[dict[str, str]]:
    packages = []
    for stanza in text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in stanza.splitlines():
            if len(line) >= 2 and line[1] == ":":
                fields[line[0]] = line[2:]
        if "P" in fields:
            packages.append(fields)
    return packages


def archive_text(archive: Path, suffix: str) -> str:
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            if member.name.endswith(suffix):
                handle = tar.extractfile(member)
                if handle is None:
                    break
                return handle.read().decode("utf-8", "replace")
    fail(f"{suffix} not found in {archive}")


def read_apkindex(version: str, artifacts: Path, refresh: bool) -> tuple[dict[str, dict], dict[str, str]]:
    branch = "v" + ".".join(version.split(".")[:2])
    repos: dict[str, dict] = {}
    hashes: dict[str, str] = {}
    for repo in REPOS:
        archive = artifacts / f"APKINDEX-{branch}-{repo}-{ARCH}.tar.gz"
        if refresh or not archive.is_file():
            download(
                f"https://dl-cdn.alpinelinux.org/alpine/{branch}/{repo}/{ARCH}/APKINDEX.tar.gz",
                archive,
            )
        hashes[repo] = sha256(archive)
        for fields in parse_stanzas(archive_text(archive, "APKINDEX")):
            repos.setdefault(
                fields["P"],
                {
                    "repo": repo,
                    "origin": fields.get("o", fields["P"]),
                    "version": fields.get("V", ""),
                    "license": fields.get("L", "NOASSERTION"),
                    "commit": fields.get("c", ""),
                },
            )
    return repos, hashes


def parse_rootfs(archive: Path) -> list[dict[str, str]]:
    return parse_stanzas(archive_text(archive, "/lib/apk/db/installed"))


def bun_version(binary: Path) -> str | None:
    pattern = re.compile(rb"(?:bun-v|Bun v)(\d+\.\d+\.\d+)")
    tail = b""
    with binary.open("rb") as handle:
        while True:
            chunk = handle.read(1 << 20)
            if not chunk:
                return None
            match = pattern.search(tail + chunk)
            if match:
                return match.group(1).decode()
            tail = (tail + chunk)[-64:]


def purl_apk(name: str, version: str, alpine_version: str) -> str:
    return (
        f"pkg:apk/alpine/{urllib.parse.quote(name, safe='-._~')}"
        f"@{urllib.parse.quote(version, safe='-._~')}"
        f"?arch={ARCH}&distro=alpine-{alpine_version}"
    )


def alpine_source_url(package: dict[str, str], repo: str, alpine_version: str) -> str:
    origin = package.get("o", package["P"])
    commit = package.get("c")
    base = "https://gitlab.alpinelinux.org/alpine/aports/-/tree"
    if commit:
        return f"{base}/{commit}/{repo}/{origin}"
    branch = ".".join(alpine_version.split(".")[:2]) + "-stable"
    return f"{base}/{branch}/{repo}/{origin}"


def alpine_package_url(name: str, repo: str, alpine_version: str) -> str:
    branch = "v" + ".".join(alpine_version.split(".")[:2])
    return f"https://pkgs.alpinelinux.org/package/{branch}/{repo}/{ARCH}/{name}"


def license_value(license_id: str) -> dict:
    if re.fullmatch(r"[A-Za-z0-9.+-]+", license_id):
        return {"expression": license_id}
    return {"license": {"name": license_id}}


def component(
    name: str,
    version: str,
    license_id: str,
    *,
    purl: str | None = None,
    hashes: list[dict[str, str]] | None = None,
    references: list[dict[str, str]] | None = None,
    properties: list[dict[str, str]] | None = None,
    component_type: str = "library",
) -> dict:
    value: dict = {
        "type": component_type,
        "name": name,
        "version": version,
        "licenses": [license_value(license_id)],
    }
    if purl:
        value["purl"] = purl
    if hashes:
        value["hashes"] = hashes
    if references:
        value["externalReferences"] = references
    if properties:
        value["properties"] = properties
    return value


def parse_bun_bundled(path: Path, bun: str) -> list[dict]:
    if not path.is_file():
        fail(f"missing {path}; fetch the Bun license text")
    items: dict[str, dict] = {}
    section = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("## "):
            section = raw[3:].strip()
            continue
        if section not in ("Linked libraries", "Polyfills") or not raw.startswith("|"):
            continue
        cells = [cell.strip() for cell in raw.strip().strip("|").split("|")]
        if len(cells) < 2 or cells[0].startswith("Library") or cells[0].startswith("---"):
            continue
        if not (cells[0].startswith("[") or cells[0].startswith("A fork of [")):
            continue
        link = re.search(r"\[`?([^`\]]+)`?\]\(([^)]+)\)", cells[0])
        if not link:
            continue
        label = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", cells[0]).replace("`", "").strip()
        license_text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", cells[1]).strip()
        items[label] = {
            "name": label,
            "version": "bundled in Bun " + bun,
            "license": license_text,
            "sourceUrl": link.group(2),
            "section": section,
        }
    return [items[key] for key in sorted(items)]


def build(manifest: dict, artifacts: Path, refresh: bool) -> dict:
    version = manifest["version"]
    alpine_version = manifest["alpine"]["version"]
    artifact = manifest["artifact"]
    rootfs_archive = artifacts / f"alpine-minirootfs-{alpine_version}-{ARCH}.tar.gz"
    if not rootfs_archive.is_file():
        fail(f"missing {rootfs_archive}; run scripts/fetch-runtime.py --alpine")
    rootfs_hash = sha256(rootfs_archive)
    if rootfs_hash != manifest["alpine"]["sha256"]:
        fail(f"rootfs sha-256 mismatch: {rootfs_hash}")

    repo_by_name, apkindex_hashes = read_apkindex(alpine_version, artifacts, refresh)
    packages = []
    for fields in parse_rootfs(rootfs_archive):
        name = fields["P"]
        mapping = repo_by_name.get(
            name,
            {"repo": "main", "origin": fields.get("o", name), "version": fields.get("V", "")},
        )
        repo = mapping["repo"]
        license_id = fields.get("L") or "NOASSERTION"
        packages.append(
            {
                "name": name,
                "version": fields.get("V", ""),
                "repo": repo,
                "origin": fields.get("o", name),
                "license": license_id,
                "commit": fields.get("c", ""),
                "sourceUrl": alpine_source_url(fields, repo, alpine_version),
                "distributionUrl": alpine_package_url(name, repo, alpine_version),
                "repositoryVersion": mapping.get("version", ""),
            }
        )
    package_index = {item["name"]: index for index, item in enumerate(packages)}
    alpine_packages = list(manifest.get("alpineRuntimePackages", []))
    alpine_packages.extend(manifest.get("nodeRuntime", {}).get("packages", []))
    for spec in alpine_packages:
        mapping = repo_by_name.get(spec["name"])
        if mapping is None or mapping["version"] != spec["version"]:
            fail(f"runtime package is not present at its pinned Alpine version: {spec['name']}")
        package_fields = {
            "P": spec["name"],
            "o": mapping["origin"],
            "c": mapping["commit"],
        }
        package = {
            "name": spec["name"],
            "version": spec["version"],
            "repo": mapping["repo"],
            "origin": mapping["origin"],
            "license": mapping["license"],
            "commit": mapping["commit"],
            "sourceUrl": alpine_source_url(package_fields, mapping["repo"], alpine_version),
            "distributionUrl": alpine_package_url(spec["name"], mapping["repo"], alpine_version),
            "repositoryVersion": mapping["version"],
        }
        if spec["name"] in package_index:
            packages[package_index[spec["name"]]] = package
        else:
            package_index[spec["name"]] = len(packages)
            packages.append(package)
    packages.sort(key=lambda item: (item["name"], item["version"]))

    runtime_binary = artifacts / f"opencode-{version}" / manifest["binary"]
    detected_bun = bun_version(runtime_binary) if runtime_binary.is_file() else None
    bun_source = manifest.get("bun", {}).get("source", {})
    bun_lgpl = manifest.get("bun", {}).get("lgpl", {})
    jsc = bun_lgpl.get("JavaScriptCore", {})
    jsc_source = jsc.get("source", {})
    tcc = bun_lgpl.get("tinycc", {})
    tcc_source = tcc.get("source", {})

    bun_license = ROOT / "docs" / "licenses" / "texts" / "Bun-LICENSE.md"
    bundled = (
        [item for item in parse_bun_bundled(bun_license, detected_bun) if item["name"] != "tinycc"]
        if detected_bun
        else []
    )

    runtime_components = [
        component(
            "opencode",
            version,
            "MIT",
            purl=f"pkg:github/anomalyco/opencode@v{version}",
            hashes=[{"alg": "SHA-256", "content": artifact["sha256"]}],
            references=[
                {"type": "distribution", "url": artifact["url"]},
                {"type": "source-distribution", "url": manifest["source"]["url"]},
                {"type": "vcs", "url": "https://github.com/anomalyco/opencode"},
            ],
            properties=[
                {"name": "source:sha256", "value": manifest["source"]["sha256"]},
                {"name": "source:ref", "value": manifest["source"]["tag"]},
            ],
            component_type="application",
        ),
    ]
    if detected_bun:
        runtime_components.append(
            component(
                "bun",
                detected_bun,
                "MIT",
                purl=f"pkg:github/oven-sh/bun@{detected_bun}",
                references=[
                    {"type": "license", "url": "https://github.com/oven-sh/bun/blob/main/LICENSE.md"},
                    {"type": "vcs", "url": "https://github.com/oven-sh/bun"},
                    *(
                        [{"type": "source-distribution", "url": bun_source["url"]}]
                        if bun_source.get("url")
                        else []
                    ),
                ],
                properties=[
                    {"name": "bun:detected-from", "value": manifest["binary"]},
                    {"name": "bun:statically-links", "value": "JavaScriptCore (LGPL-2.0-only), tinycc (LGPL-2.1-only)"},
                    *(
                        [
                            {"name": "source:sha256", "value": bun_source.get("sha256", "")},
                            {"name": "source:ref", "value": bun_source.get("tag", "")},
                            {"name": "source:commit", "value": bun_source.get("commit", "")},
                        ]
                        if bun_source.get("url")
                        else []
                    ),
                ],
                component_type="application",
            )
        )
        runtime_components.append(
            component(
                "JavaScriptCore (WebKit)",
                "bundled in Bun " + detected_bun,
                jsc.get("license", "LGPL-2.0-only"),
                references=[
                    {"type": "license", "url": "https://github.com/oven-sh/bun/blob/main/LICENSE.md"},
                    {"type": "vcs", "url": "https://github.com/oven-sh/WebKit"},
                    *(
                        [{"type": "source-distribution", "url": jsc_source["url"]}]
                        if jsc_source.get("url")
                        else []
                    ),
                ],
                properties=[
                    {"name": "bun:statically-linked", "value": "true"},
                    *(
                        [{"name": "source:commit", "value": jsc_source.get("commit", "")}]
                        if jsc_source.get("url")
                        else []
                    ),
                ],
            )
        )
        runtime_components.append(
            component(
                "tinycc",
                "bundled in Bun " + detected_bun,
                tcc.get("license", "LGPL-2.1-only"),
                references=[
                    {"type": "license", "url": "https://github.com/oven-sh/bun/blob/main/LICENSE.md"},
                    {"type": "vcs", "url": "https://github.com/oven-sh/tinycc"},
                    *(
                        [{"type": "source-distribution", "url": tcc_source["url"]}]
                        if tcc_source.get("url")
                        else []
                    ),
                ],
                properties=[
                    {"name": "bun:statically-linked", "value": "true"},
                    *(
                        [{"name": "source:commit", "value": tcc_source.get("commit", "")}]
                        if tcc_source.get("url")
                        else []
                    ),
                ],
            )
        )
        bundled_components = []
        for item in bundled:
            bundled_components.append(
                component(
                    item["name"],
                    item["version"],
                    item["license"],
                    references=[
                        {"type": "license", "url": "https://github.com/oven-sh/bun/blob/main/LICENSE.md"},
                        {"type": "vcs", "url": item["sourceUrl"]},
                    ],
                    properties=[
                        {"name": "bun:bundled-section", "value": item["section"]},
                        {"name": "bun:presence", "value": "upstream-listed; not verified in this binary"},
                    ],
                )
            )

    alpine_components = []
    for package in packages:
        alpine_components.append(
            component(
                package["name"],
                package["version"],
                package["license"],
                purl=purl_apk(package["name"], package["version"], alpine_version),
                references=[
                    {"type": "distribution", "url": package["distributionUrl"]},
                    {"type": "vcs", "url": package["sourceUrl"]},
                ],
                properties=[
                    {"name": "alpine:repository", "value": package["repo"]},
                    {"name": "alpine:origin", "value": package["origin"]},
                    {"name": "alpine:license-raw", "value": package["license"]},
                    {"name": "alpine:repository-version", "value": package.get("repositoryVersion", "")},
                ],
            )
        )

    license_summary: dict[str, int] = {}
    for package in packages:
        license_summary[package["license"]] = license_summary.get(package["license"], 0) + 1

    android_libs = []
    for key in ("proot", "libtalloc", "libandroid-shmem"):
        spec = manifest[key]
        source = spec["source"]
        android_libs.append(
            {
                "name": spec["name"],
                "version": spec["version"],
                "license": spec["license"],
                "distributionUrl": spec["url"],
                "sha256": spec["sha256"],
                "sourceUrl": source["url"],
                "sourceSha256": source["sha256"],
                "sourceRef": source.get("tag") or source.get("commit", ""),
            }
        )
        runtime_components.append(
            component(
                spec["name"],
                spec["version"],
                spec["license"],
                hashes=[{"alg": "SHA-256", "content": spec["sha256"]}],
                references=[
                    {"type": "distribution", "url": spec["url"]},
                    {"type": "vcs", "url": source["url"]},
                ],
                properties=[
                    {"name": "source:sha256", "value": source["sha256"]},
                    {"name": "source:ref", "value": source.get("tag") or source.get("commit", "")},
                ],
                component_type="application" if key == "proot" else "library",
            )
        )

    known_gaps = [
        "Bun statically links JavaScriptCore (LGPL-2.0-only) and tinycc "
        "(LGPL-2.1-only); the opencode ELF has no shared JSC/tinycc NEEDED "
        "entries and embeds JSC source paths. Bun's LICENSE.md describes "
        "source/relink steps; whether object files must also be offered "
        "needs legal review before release.",
        "The OpenCode dependency SBOM is a production/optional lockfile "
        "closure; verify the bundled set against the binary at release and "
        "keep CC-BY attribution notices current.",
        "PRoot dynamically links libtalloc.so.2. The pinned talloc 2.5.0 "
        "source is LGPL-3.0-or-later, while the Termux deb copyright symlink "
        "points to GPL-3.0.txt; treat the upstream source as authoritative "
        "and review the packaging discrepancy.",
        "PRoot and libtalloc are pinned to Termux packages; verify each "
        "published deb was built from the pinned source before release.",
        "Three Alpine packages (alpine-baselayout, alpine-baselayout-data "
        "and alpine-keys) ship no separate copyright file; their notices use "
        "package metadata and the upstream license.",
        "UI dependencies (React, Vite, CodeMirror, xterm.js) are not added "
        "yet; regenerate this inventory when ui/package.json lands.",
        "Bun-bundled library versions and presence are taken from Bun's "
        "LICENSE.md, not from the binary; verify at release. Bun also "
        "lists lint/fmt/typechecker code ports in the same table; those "
        "are covered by the Bun LICENSE but not split into components.",
    ]

    package = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
    app = {
        "type": "application",
        "name": "opencode-mobile",
        "version": package["version"],
        "licenses": [{"expression": package.get("license", "Apache-2.0")}],
        "purl": f"pkg:generic/opencode-mobile@{package['version']}",
        "externalReferences": [{"type": "vcs", "url": "https://github.com/spector-dev9/Opencode-mobile"}],
    }
    components = sorted(
        runtime_components + bundled_components + alpine_components,
        key=lambda item: (item["name"].lower(), item["version"]),
    )

    return {
        "app": app,
        "runtime": runtime_components,
        "androidLibs": android_libs,
        "bunBundled": bundled,
        "packages": packages,
        "licenseSummary": dict(sorted(license_summary.items())),
        "knownGaps": known_gaps,
        "bun": {"version": detected_bun, "source": bun_source, "lgpl": bun_lgpl},
        "alpine": {
            "version": alpine_version,
            "rootfsSha256": rootfs_hash,
            "apkindexSha256": apkindex_hashes,
        },
        "components": components,
    }


def sbom(data: dict) -> dict:
    body = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": data["app"],
            "properties": [
                {"name": "alpine:version", "value": data["alpine"]["version"]},
                {"name": "alpine:rootfs-sha256", "value": data["alpine"]["rootfsSha256"]},
                {"name": "alpine:apkindex-sha256:main", "value": data["alpine"]["apkindexSha256"]["main"]},
                {"name": "alpine:apkindex-sha256:community", "value": data["alpine"]["apkindexSha256"]["community"]},
            ],
        },
        "components": data["components"],
    }
    serial = uuid.uuid5(
        uuid.NAMESPACE_URL,
        json.dumps(body, sort_keys=True),
    )
    body["serialNumber"] = f"urn:uuid:{serial}"
    return body


def license_label(item: dict) -> str:
    value = item["licenses"][0]
    if "expression" in value:
        return value["expression"]
    return value["license"]["name"]


def notices(data: dict) -> str:
    lines = [
        "# Third-party notices",
        "",
        "Generated by `scripts/build-licenses.py` from `runtime/manifest.json`,",
        f"Alpine {data['alpine']['version']} and Bun's LICENSE.md. Do not edit by hand.",
        "",
        "This file is a draft. The upstream license text governs; see",
        "`texts/` and the source links below.",
        "",
        "## Runtime and app",
        "",
        "| Component | Version | License | Source |",
        "| --- | --- | --- | --- |",
    ]
    app = data["app"]
    lines.append(
        f"| {app['name']} | {app['version']} | {app['licenses'][0]['expression']} | {app['externalReferences'][0]['url']} |"
    )
    for item in data["runtime"]:
        source = next(
            (ref["url"] for ref in item.get("externalReferences", []) if ref["type"] == "vcs"),
            "",
        )
        lines.append(
            f"| {item['name']} | {item['version']} | {license_label(item)} | {source} |"
        )
    lines += [
        "",
        "## Bun bundled libraries",
        "",
        "Listed by Bun's LICENSE.md; presence in this binary is not",
        "verified and versions are not pinned.",
        "",
        "| Library | License | Source |",
        "| --- | --- | --- |",
    ]
    for item in data["bunBundled"]:
        lines.append(
            f"| {item['name']} | {item['license']} | {item['sourceUrl']} |"
        )
    lines += [
        "",
        "## Alpine packages",
        "",
        "| Package | Version | License | Source |",
        "| --- | --- | --- | --- |",
    ]
    for package in data["packages"]:
        lines.append(
            f"| {package['name']} | {package['version']} | {package['license']} | {package['sourceUrl']} |"
        )
    lines += [
        "",
        "## Copyright notices",
        "",
        "- OpenCode: Copyright (c) 2025 opencode.",
        "- Bun: see `texts/Bun-LICENSE.md` (upstream file has no separate",
        "  copyright line).",
        "- Alpine packages: copyright notices are in the linked sources.",
        "- PRoot: Copyright (C) STMicroelectronics and contributors.",
        "- libtalloc: Copyright (C) Andrew Tridgell 2004, Stefan Metzmacher 2006.",
        "- libandroid-shmem: Copyright (c) 2013 Sergii Pylypenko, 2017 Fredrik Fornwall.",
        "- opencode-mobile: see the root `LICENSE`.",
        "",
        "## License summary",
        "",
        "| License | Packages |",
        "| --- | --- |",
    ]
    for license_id, count in data["licenseSummary"].items():
        lines.append(f"| {license_id} | {count} |")
    alpine_copyright_path = ROOT / "docs" / "licenses" / "alpine-copyright.json"
    if alpine_copyright_path.is_file():
        alpine_copyright = json.loads(alpine_copyright_path.read_text(encoding="utf-8"))
        lines += [
            "",
            "## Alpine package copyright notices",
            "",
            "Collected from the pinned APKBUILDs and upstream sources; evidence:",
            "[alpine-copyright.json](alpine-copyright.json).",
            "",
            "| Package | License | Copyright |",
            "| --- | --- | --- |",
        ]
        for item in alpine_copyright["packages"]:
            lines.append(f"| {item['name']} | {item['license']} | {item['copyright']} |")
        lines += [
            "",
            "`alpine-baselayout`, `alpine-baselayout-data` and `alpine-keys` ship",
            "no separate copyright file; their notices are based on package",
            "metadata and the upstream license.",
        ]
    deps_path = ROOT / "docs" / "licenses" / "opencode-deps.json"
    if deps_path.is_file():
        deps = json.loads(deps_path.read_text(encoding="utf-8"))
        lines += [
            "",
            "## OpenCode dependency closure",
            "",
            f"The production/optional closure of `packages/opencode` contains "
            f"{deps['packageCount']} unique npm and workspace packages at "
            f"{deps['instanceCount']} install paths.",
            "",
            "Full inventory: [opencode-deps.json](opencode-deps.json); SBOM:",
            "[opencode-deps.cdx.json](opencode-deps.cdx.json).",
            "",
            "| License | Packages |",
            "| --- | --- |",
        ]
        for license_id, count in deps["licenseSummary"].items():
            lines.append(f"| {license_id} | {count} |")
        lines += [
            "",
            "Attribution is required for `caniuse-lite` (CC-BY-4.0) and",
            "`spdx-exceptions` (CC-BY-3.0). Dual-licensed packages",
            "(`dompurify`: MPL-2.0 OR Apache-2.0; `json-schema`: AFL-2.1 OR",
            "BSD-3-Clause; `type-fest`: MIT OR CC0-1.0) are used under the",
            "permissive option. No GPL/AGPL packages were found in this closure.",
            "",
            "CC-BY attribution:",
            "",
            "- `caniuse-lite` — caniuse-lite contributors, CC-BY-4.0,",
            "  <https://github.com/browserslist/caniuse-lite>.",
            "- `spdx-exceptions` — SPDX contributors, CC-BY-3.0,",
            "  <https://github.com/kemitchell/spdx-exceptions.json>.",
        ]
    lines += [
        "",
        "## Known gaps",
        "",
    ]
    for gap in data["knownGaps"]:
        lines.append(f"- {gap}")
    texts = [
        "Apache-2.0",
        "MIT",
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-3.0-only",
        "BSD-2-Clause",
        "MPL-2.0",
        "Zlib",
        "LGPL-2.0-only",
        "LGPL-2.1-only",
        "LGPL-3.0-or-later",
        "BSD-3-Clause",
        "CC-BY-3.0",
        "CC-BY-4.0",
    ]
    lines += [
        "",
        "## License texts",
        "",
        "| License | Text |",
        "| --- | --- |",
    ]
    for license_id in texts:
        lines.append(f"| {license_id} | [texts/{license_id}.txt](texts/{license_id}.txt) |")
    lines += [
        "| Bun bundled components | [texts/Bun-LICENSE.md](texts/Bun-LICENSE.md) |",
        "",
        "MIT and Apache-2.0 texts are generic; the copyright notices above",
        "and in the source packages apply.",
        "",
    ]
    return "\n".join(lines)


def source_offer(data: dict) -> str:
    gpl_packages = [
        package
        for package in data["packages"]
        if "GPL" in package["license"] or "LGPL" in package["license"] or "MPL" in package["license"]
    ]
    lines = [
        "# Source offer (draft)",
        "",
        "This release includes GPL/LGPL/MPL components. The linked Alpine",
        "aports revisions contain the build recipes and patches; complete",
        "corresponding source will be archived with each release or provided",
        "on request from the project maintainer for at least three years from",
        "the date of distribution. The offer is valid for anyone who received",
        "the binary.",
        "",
        "## Alpine packages",
        "",
        "| Package | Version | License | Build recipe / source |",
        "| --- | --- | --- | --- |",
    ]
    for package in gpl_packages:
        lines.append(
            f"| {package['name']} | {package['version']} | {package['license']} | {package['sourceUrl']} |"
        )
    lines += [
        "",
        "## Android libraries",
        "",
        "| Library | Version | License | Source archive | SHA-256 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in data["androidLibs"]:
        if "GPL" not in item["license"] and "LGPL" not in item["license"]:
            continue
        lines.append(
            f"| {item['name']} | {item['version']} | {item['license']} | {item['sourceUrl']} | {item['sourceSha256']} |"
        )
    lines += [
        "",
        "libandroid-shmem is BSD-3-Clause and is covered by the notices.",
        "",
        "PRoot's ELF `NEEDED` entries include `libtalloc.so.2` and",
        "`libandroid-shmem.so`, so talloc is dynamically linked. The pinned",
        "talloc 2.5.0 `LICENSE` and source headers are LGPL-3.0-or-later;",
        "the Termux deb's `copyright` symlink points to `GPL-3.0.txt`.",
        "Use the upstream source as authoritative and review this packaging",
        "discrepancy before release.",
        "",
        "## Bun (LGPL components)",
        "",
        "Bun statically links JavaScriptCore (LGPL-2.0-only) and tinycc",
        "(LGPL-2.1-only). Bun publishes relinking instructions in its",
        "LICENSE.md: https://github.com/oven-sh/bun/blob/main/LICENSE.md.",
        "Bun's other bundled libraries are listed in NOTICES.md; review",
        "their licenses before release.",
        "",
        "The `opencode` ELF's `NEEDED` entries are `libstdc++.so.6`,",
        "`libc.musl-aarch64.so.1` and `libgcc_s.so.1`; no shared",
        "JavaScriptCore or tinycc is listed, and JavaScriptCore source paths",
        "are embedded in the binary. This confirms static linkage of the",
        "LGPL components. Review packet: [bun-lgpl-review.md](bun-lgpl-review.md).",
        "",
    ]
    bun = data.get("bun", {})
    bun_source = bun.get("source", {})
    if bun_source.get("url"):
        lines += [
            f"Bun {bun.get('version') or ''} source archive:",
            bun_source["url"],
            f"(commit {bun_source.get('commit', 'unknown')}; sha256",
            f"{bun_source.get('sha256', 'not recorded')}).",
            "",
        ]
    bun_lgpl = bun.get("lgpl", {})
    if bun_lgpl:
        lines += [
            "| LGPL component | License | Source archive / repository | Commit |",
            "| --- | --- | --- | --- |",
        ]
        notes = []
        for name, item in bun_lgpl.items():
            source = item.get("source", {})
            lines.append(
                f"| {name} | {item.get('license', '')} | {source.get('url', '')} | {source.get('commit', '')} |"
            )
            if source.get("note"):
                notes.append(f"- {name}: {source['note']}")
        lines.append("")
        if notes:
            lines += notes
            lines.append("")
    lines += [
        "## OpenCode dependency closure",
        "",
        "The production/optional lockfile closure contains no GPL/AGPL",
        "packages. MPL/AFL dual-licensed packages are used under their",
        "permissive options; CC-BY attribution is recorded in NOTICES.md.",
        "",
        "Per-package Alpine copyright notices are recorded in",
        "alpine-copyright.json and NOTICES.md.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACTS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    data = build(manifest, args.artifacts, args.refresh)
    outputs = {
        "components.json": json.dumps(
            {
                "schema": 1,
                "app": data["app"],
                "alpine": data["alpine"],
                "runtime": data["runtime"],
                "androidLibs": data["androidLibs"],
                "bun": data["bun"],
                "bunBundled": data["bunBundled"],
                "packages": data["packages"],
                "licenseSummary": data["licenseSummary"],
                "knownGaps": data["knownGaps"],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        "sbom.cdx.json": json.dumps(sbom(data), indent=2, sort_keys=True) + "\n",
        "NOTICES.md": notices(data),
        "SOURCE-OFFER.md": source_offer(data),
    }

    if args.check:
        stale = []
        for name, content in outputs.items():
            path = args.out / name
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                stale.append(str(path))
        if stale:
            print("stale: " + ", ".join(stale), file=sys.stderr)
            return 1
        print("license inventory is current")
        return 0

    args.out.mkdir(parents=True, exist_ok=True)
    for name, content in outputs.items():
        (args.out / name).write_text(content, encoding="utf-8")
        print(f"wrote {args.out / name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
