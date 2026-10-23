#!/usr/bin/env python3
"""Fetch and verify the pinned OpenCode runtime artifact.

Phase 2 spike tooling. Never executes the artifact; verification and
extraction only. Downloads to runtime/artifacts/ (gitignored).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tarfile
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MANIFEST = os.path.join(ROOT, "runtime", "manifest.json")
DEFAULT_DEST = os.path.join(ROOT, "runtime", "artifacts")
MAX_BYTES = 512 * 1024 * 1024


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: str) -> None:
    partial = destination + ".part"
    request = urllib.request.Request(url, headers={"User-Agent": "opencode-mobile-spike"})
    received = 0
    with urllib.request.urlopen(request, timeout=60) as response, open(partial, "wb") as output:
        length = response.headers.get("Content-Length")
        total = int(length) if length and length.isdigit() else 0
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            received += len(chunk)
            if received > MAX_BYTES:
                fail("artifact exceeds the 512 MiB ceiling")
            output.write(chunk)
        if total and received != total:
            fail(f"incomplete download: {received} of {total} bytes")
    os.replace(partial, destination)


def safe_extract(archive: str, destination: str) -> None:
    root = os.path.realpath(destination)
    os.makedirs(destination, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            name = member.name
            if name.startswith("/") or ".." in name.split("/"):
                fail(f"unsafe path in archive: {name}")
            target = os.path.realpath(os.path.join(destination, name))
            if target != root and not target.startswith(root + os.sep):
                fail(f"archive escapes its destination: {name}")
            if member.issym() or member.islnk():
                link = member.linkname
                if os.path.isabs(link):
                    fail(f"absolute link in archive: {name} -> {link}")
                resolved = os.path.realpath(os.path.join(os.path.dirname(target), link))
                if resolved != root and not resolved.startswith(root + os.sep):
                    fail(f"link escapes its destination: {name} -> {link}")
        # "data" rejects absolute paths, links outside the destination and
        # device files while preserving ordinary symlinks within the tree.
        tar.extractall(destination, members=members, filter="data")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--dest", default=DEFAULT_DEST)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    with open(args.manifest, encoding="utf-8") as handle:
        manifest = json.load(handle)

    artifact = manifest["artifact"]
    version = manifest["version"]
    expected = artifact["sha256"].lower()
    archive = os.path.join(args.dest, f"opencode-termux-{version}-aarch64.tar.gz")
    extracted = os.path.join(args.dest, f"opencode-{version}")

    os.makedirs(args.dest, exist_ok=True)

    if args.force or not os.path.isfile(archive):
        print(f"downloading {artifact['url']}")
        with tempfile.TemporaryDirectory() as tmp:
            staged = os.path.join(tmp, os.path.basename(archive))
            download(artifact["url"], staged)
            actual = sha256(staged)
            if actual != expected:
                fail(f"sha-256 mismatch: expected {expected}, got {actual}")
            os.replace(staged, archive)
    else:
        actual = sha256(archive)
        if actual != expected:
            fail(f"cached artifact sha-256 mismatch: expected {expected}, got {actual}")

    print(f"verified sha-256 {expected}")

    if args.force or not os.path.isdir(extracted):
        with tempfile.TemporaryDirectory() as tmp:
            staging = os.path.join(tmp, "runtime")
            safe_extract(archive, staging)
            if os.path.isdir(extracted):
                import shutil

                shutil.rmtree(extracted)
            os.replace(staging, extracted)

    result = {
        "version": version,
        "archive": archive,
        "artifactSha256": expected,
        "runtimeDir": extracted,
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
