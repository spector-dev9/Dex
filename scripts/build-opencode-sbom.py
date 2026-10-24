#!/usr/bin/env python3
"""Build an SBOM for OpenCode's production dependency closure.

The input is the pinned upstream source archive. Its bun.lock is a
JSON-with-trailing-commas document; the closure starts at the
``packages/opencode`` workspace and follows production and optional
dependencies using the lockfile's install paths. Nested versions are
kept distinct; duplicate installs of one version are aggregated.
Licenses come from the npm registry and are cached under
runtime/artifacts/ (gitignored).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import random
import re
import sys
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
from collections import Counter, deque
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "runtime" / "manifest.json"
ARTIFACTS = ROOT / "runtime" / "artifacts"
OUT = ROOT / "docs" / "licenses"
CACHE = ARTIFACTS / "opencode-npm-cache.json"

# Licenses that the registry does not expose for these GitHub/file packages.
OVERRIDES = {
    "@openauthjs/openauth@0.0.0-20250322224806": {
        "license": "MIT",
        "source": "https://github.com/anomalyco/openauth",
    },
    "poe-oauth@0.0.8": {
        "license": "MIT",
        "source": "https://github.com/poe-platform/poe-code",
    },
}

SPDX_IDS = {
    "0BSD",
    "AFL-2.1",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "BlueOak-1.0.0",
    "CC-BY-3.0",
    "CC-BY-4.0",
    "CC0-1.0",
    "GPL-2.0-only",
    "GPL-2.0-or-later",
    "GPL-3.0-only",
    "GPL-3.0-or-later",
    "ISC",
    "LGPL-2.1-only",
    "LGPL-3.0-or-later",
    "MIT",
    "MPL-2.0",
    "Python-2.0",
    "Unlicense",
    "WTFPL",
    "Zlib",
}


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strip_jsonc(text: str) -> str:
    out = []
    in_string = False
    escaped = False
    index = 0
    while index < len(text):
        char = text[index]
        if in_string:
            out.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        else:
            if char == '"':
                in_string = True
                out.append(char)
            elif char == ",":
                next_index = index + 1
                while next_index < len(text) and text[next_index] in " \t\r\n":
                    next_index += 1
                if next_index < len(text) and text[next_index] in "}]":
                    pass
                else:
                    out.append(char)
            else:
                out.append(char)
        index += 1
    return "".join(out)


def download(url: str, destination: Path) -> None:
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "opencode-mobile-s9"})
    print(f"downloading {url}")
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            output.write(chunk)
    os.replace(partial, destination)


def source_archive(manifest: dict) -> Path:
    source = manifest["source"]
    archive = ARTIFACTS / f"opencode-source-{manifest['version']}.tar.gz"
    if not archive.is_file():
        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ARTIFACTS) as tmp:
            staged = Path(tmp) / archive.name
            download(source["url"], staged)
            actual = sha256(staged)
            if actual != source["sha256"]:
                fail(f"source sha-256 mismatch: expected {source['sha256']}, got {actual}")
            os.replace(staged, archive)
    actual = sha256(archive)
    if actual != source["sha256"]:
        fail(f"cached source sha-256 mismatch: expected {source['sha256']}, got {actual}")
    return archive


def read_lock(archive: Path, version: str) -> tuple[dict, str]:
    with tarfile.open(archive, "r:gz") as tar:
        member = tar.getmember(f"opencode-{version}/bun.lock")
        handle = tar.extractfile(member)
        if handle is None:
            fail("cannot read bun.lock from the source archive")
        raw = handle.read()
    return json.loads(strip_jsonc(raw.decode("utf-8"))), hashlib.sha256(raw).hexdigest()


def load_registry(name: str, version: str, cache: dict, refresh: bool) -> dict:
    key = f"{name}@{version}"
    if not refresh and key in cache:
        return cache[key]
    url = (
        "https://registry.npmjs.org/"
        + urllib.parse.quote(name, safe="@")
        + "/"
        + urllib.parse.quote(version, safe="")
    )
    last_error = ""
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "opencode-mobile-s9"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = json.load(response)
            return {
                "ok": True,
                "license": data.get("license"),
                "licenses": data.get("licenses"),
                "repository": data.get("repository"),
                "homepage": data.get("homepage"),
            }
        except Exception as error:  # noqa: BLE001 - network failures are recorded
            last_error = str(error)
            if attempt < 3:
                time.sleep(1 + attempt * 2 + random.random())
    return {"ok": False, "error": last_error}


def raw_license(value: dict) -> str | None:
    license_value = value.get("license") or value.get("licenses")
    if isinstance(license_value, list):
        license_value = license_value[0] if license_value else None
    if isinstance(license_value, dict):
        license_value = license_value.get("type")
    if not license_value:
        return None
    return str(license_value).strip()


def normalize_license(value: str | None) -> str:
    if not value:
        return "NOASSERTION"
    value = value.strip().replace("apache-2.0", "Apache-2.0")
    value = value.rstrip("*").strip()
    if value in SPDX_IDS:
        return value
    expression = value.strip("()")
    tokens = re.split(r"\s+(AND|OR|WITH)\s+", expression)
    if all(token in SPDX_IDS or token in ("AND", "OR", "WITH") for token in tokens):
        return value
    return value


def license_json(value: str) -> dict:
    if value == "NOASSERTION":
        return {"expression": "NOASSERTION"}
    if re.fullmatch(r"[A-Za-z0-9.+-]+", value):
        return {"expression": value}
    expression = value.strip("()")
    tokens = re.split(r"\s+(AND|OR|WITH)\s+", expression)
    if all(token in SPDX_IDS or token in ("AND", "OR", "WITH") for token in tokens):
        return {"expression": value}
    return {"license": {"name": value}}


def repository_url(value: dict) -> str:
    repository = value.get("repository")
    if isinstance(repository, dict):
        repository = repository.get("url")
    if not isinstance(repository, str) or not repository:
        homepage = value.get("homepage")
        return homepage if isinstance(homepage, str) else ""
    url = repository
    for prefix in ("git+ssh://git@", "git+https://", "git://", "git+http://", "ssh://git@"):
        if url.startswith(prefix):
            url = "https://" + url[len(prefix) :]
            break
    url = url.removesuffix(".git")
    if url.startswith("https://github.com/") and "#" in url:
        url = url.split("#", 1)[0]
    return url


def purl(name: str, version: str) -> str:
    encoded_version = urllib.parse.quote(version, safe=".-+_")
    if name.startswith("@") and "/" in name:
        scope, package = name[1:].split("/", 1)
        return f"pkg:npm/%40{urllib.parse.quote(scope)}/{urllib.parse.quote(package)}@{encoded_version}"
    return f"pkg:npm/{urllib.parse.quote(name)}@{encoded_version}"


def integrity_hex(value: str) -> str | None:
    if not value.startswith("sha512-"):
        return None
    try:
        return base64.b64decode(value[7:]).hex()
    except ValueError:
        return None


def build(manifest: dict, refresh: bool) -> tuple[dict, dict]:
    archive = source_archive(manifest)
    lock, lock_hash = read_lock(archive, manifest["version"])
    entries: dict[str, dict] = {}
    for key, value in lock["packages"].items():
        resolved = value[0]
        if "@" in resolved[1:]:
            name, version = resolved.rsplit("@", 1)
        else:
            name, version = resolved, ""
        info = value[2] if len(value) > 2 and isinstance(value[2], dict) else {}
        entries[key] = {
            "key": key,
            "name": name,
            "version": version,
            "info": info,
            "integrity": value[3] if len(value) > 3 else "",
        }

    workspaces = {
        value.get("name"): (key, value)
        for key, value in lock["workspaces"].items()
        if value.get("name")
    }
    catalog = lock.get("catalog", {})

    def resolve(parent: str, name: str, requirement: str) -> tuple[str, str] | None:
        if name in workspaces:
            return "workspace", name
        requirement = str(catalog.get(str(requirement), requirement))
        if requirement.startswith("workspace:"):
            return "workspace", name
        path = parent
        while True:
            candidate = f"{path}/{name}" if path else name
            if candidate in entries:
                return "package", candidate
            if not path:
                return None
            path = path.rsplit("/", 1)[0] if "/" in path else ""

    seen: set[str] = set()
    unresolved: list[dict[str, str]] = []
    queue: deque[tuple[str, str]] = deque([("workspace", "opencode")])
    while queue:
        kind, name = queue.popleft()
        if name in seen:
            continue
        seen.add(name)
        if kind == "workspace":
            workspace = workspaces.get(name)
            if not workspace:
                unresolved.append({"name": name, "reason": "workspace missing"})
                continue
            parent = name
            dependencies = dict(workspace[1].get("dependencies", {}))
            dependencies.update(workspace[1].get("optionalDependencies", {}))
        else:
            parent = name
            info = entries[name]["info"]
            dependencies = dict(info.get("dependencies", {}))
            dependencies.update(info.get("optionalDependencies", {}))
        for dependency, spec in dependencies.items():
            resolved = resolve(parent, dependency, str(spec))
            if not resolved:
                unresolved.append(
                    {
                        "name": dependency,
                        "parent": parent,
                        "reason": f"lock path missing for {spec}",
                    }
                )
                continue
            queue.append(resolved)

    cache: dict = {}
    if CACHE.is_file():
        cache = json.loads(CACHE.read_text(encoding="utf-8"))
    external_by_id = {}
    for key in seen:
        if key not in workspaces:
            entry = entries[key]
            external_by_id[(entry["name"], entry["version"])] = entry
    external = list(external_by_id.values())
    pending = [
        entry
        for entry in external
        if refresh or f"{entry['name']}@{entry['version']}" not in cache
    ]
    if pending:
        print(f"fetching {len(pending)} npm registry records", file=sys.stderr)
        with ThreadPoolExecutor(max_workers=16) as executor:
            futures = {
                executor.submit(load_registry, entry["name"], entry["version"], cache, True): entry
                for entry in pending
            }
            for index, future in enumerate(as_completed(futures), 1):
                entry = futures[future]
                cache[f"{entry['name']}@{entry['version']}"] = future.result()
                if index % 100 == 0:
                    print(f"  {index}/{len(pending)}", file=sys.stderr)
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(json.dumps(cache, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    packages_by_id: dict[tuple[str, str], dict] = {}
    for key in seen:
        if key in workspaces:
            workspace_key, workspace = workspaces[key]
            name = workspace.get("name", key)
            version = workspace.get("version", manifest["version"])
            license_id = "MIT"
            source = "https://github.com/anomalyco/opencode"
            integrity = ""
            workspace_flag = True
            workspace_dir = workspace_key
        else:
            entry = entries[key]
            name = entry["name"]
            version = entry["version"]
            registry = cache.get(f"{name}@{version}", {})
            override = OVERRIDES.get(f"{name}@{version}", {})
            license_id = normalize_license(raw_license(registry)) if registry.get("ok") else "NOASSERTION"
            if override.get("license"):
                license_id = override["license"]
            source = override.get("source") or repository_url(registry)
            integrity = entry["integrity"]
            workspace_flag = False
            workspace_dir = ""
        identity = (name, version)
        record = packages_by_id.get(identity)
        if record is None:
            record = {
                "name": name,
                "version": version,
                "license": license_id,
                "sourceUrl": source,
                "integrity": integrity,
                "workspace": workspace_flag,
                "workspaceDir": workspace_dir,
                "installPaths": set(),
            }
            packages_by_id[identity] = record
        else:
            if record["license"] != license_id:
                fail(f"conflicting licenses for {name}@{version}")
            if record["workspace"] != workspace_flag:
                fail(f"package/workspace collision for {name}@{version}")
            if record["sourceUrl"] and source and record["sourceUrl"] != source:
                fail(f"conflicting source URLs for {name}@{version}")
            if not record["sourceUrl"]:
                record["sourceUrl"] = source
            if record["integrity"] and integrity and record["integrity"] != integrity:
                fail(f"conflicting integrity for {name}@{version}")
            if not record["integrity"]:
                record["integrity"] = integrity
        record["installPaths"].add(key)

    packages = []
    license_counts: Counter[str] = Counter()
    unresolved_licenses = []
    for identity in sorted(packages_by_id, key=lambda item: (item[0].lower(), item[1])):
        record = packages_by_id[identity]
        if record["license"] == "NOASSERTION":
            unresolved_licenses.append(f"{record['name']}@{record['version']}")
        license_counts[record["license"]] += 1
        package = {
            "name": record["name"],
            "version": record["version"],
            "license": record["license"],
            "purl": purl(record["name"], record["version"]),
            "sourceUrl": record["sourceUrl"],
            "integrity": record["integrity"],
            "workspace": record["workspace"],
            "installPaths": sorted(record["installPaths"]),
        }
        if record["workspaceDir"]:
            package["workspaceDir"] = record["workspaceDir"]
        packages.append(package)

    data = {
        "schema": 1,
        "source": manifest["source"],
        "lockfileSha256": lock_hash,
        "root": "packages/opencode",
        "packageCount": len(packages),
        "instanceCount": sum(len(item["installPaths"]) for item in packages),
        "workspaceCount": sum(1 for item in packages if item["workspace"]),
        "externalCount": sum(1 for item in packages if not item["workspace"]),
        "licenseSummary": dict(sorted(license_counts.items())),
        "unresolvedLicenses": sorted(unresolved_licenses),
        "unresolvedPackages": sorted(
            unresolved, key=lambda item: (item["name"], item.get("parent", ""))
        ),
        "packages": packages,
    }

    components = []
    for item in packages:
        references = []
        if item["sourceUrl"]:
            references.append({"type": "vcs", "url": item["sourceUrl"]})
        references.append(
            {"type": "distribution", "url": f"https://www.npmjs.com/package/{item['name']}/v/{item['version']}"}
        )
        component = {
            "type": "library",
            "bom-ref": item["purl"],
            "name": item["name"],
            "version": item["version"],
            "purl": item["purl"],
            "licenses": [license_json(item["license"])],
            "externalReferences": references,
            "properties": [
                {"name": "npm:workspace", "value": str(item["workspace"]).lower()},
                {"name": "npm:install-paths", "value": ";".join(item["installPaths"])},
            ],
        }
        if item.get("workspaceDir"):
            component["properties"].append(
                {"name": "npm:workspace-dir", "value": item["workspaceDir"]}
            )
        if item["integrity"]:
            component["properties"].append({"name": "npm:integrity", "value": item["integrity"]})
        digest = integrity_hex(item["integrity"])
        if digest:
            component["hashes"] = [{"alg": "SHA-512", "content": digest}]
        components.append(component)

    app = {
        "type": "application",
        "bom-ref": f"pkg:github/anomalyco/opencode@v{manifest['version']}",
        "name": "opencode",
        "version": manifest["version"],
        "licenses": [{"expression": "MIT"}],
        "purl": f"pkg:github/anomalyco/opencode@v{manifest['version']}",
        "externalReferences": [
            {"type": "distribution", "url": manifest["artifact"]["url"]},
            {"type": "source-distribution", "url": manifest["source"]["url"]},
            {"type": "vcs", "url": "https://github.com/anomalyco/opencode"},
        ],
    }
    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": app,
            "properties": [
                {"name": "opencode:lockfile-sha256", "value": lock_hash},
                {"name": "opencode:root", "value": "packages/opencode"},
                {"name": "opencode:scope", "value": "production and optional dependency closure"},
                {"name": "opencode:source-sha256", "value": manifest["source"]["sha256"]},
            ],
        },
        "components": components,
    }
    serial = uuid.uuid5(uuid.NAMESPACE_URL, json.dumps(sbom, sort_keys=True))
    sbom["serialNumber"] = f"urn:uuid:{serial}"
    return data, sbom


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--out", type=Path, default=OUT)
    parser.add_argument("--refresh", action="store_true", help="ignore the npm metadata cache")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    data, sbom = build(manifest, args.refresh)
    outputs = {
        "opencode-deps.json": json.dumps(data, indent=2, sort_keys=True) + "\n",
        "opencode-deps.cdx.json": json.dumps(sbom, indent=2, sort_keys=True) + "\n",
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
        print("opencode dependency SBOM is current")
        return 0

    args.out.mkdir(parents=True, exist_ok=True)
    for name, content in outputs.items():
        (args.out / name).write_text(content, encoding="utf-8")
        print(f"wrote {args.out / name}")
    print(
        f"{data['packageCount']} packages, "
        f"{len(data['unresolvedLicenses'])} unresolved licenses",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
