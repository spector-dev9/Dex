#!/usr/bin/env python3
"""Stage the pinned runtime, PRoot and Alpine rootfs for the Phase 2 harness.

This script is spike tooling only. It never executes downloaded files. It
downloads and verifies the artifacts through scripts/fetch-runtime.py, then
creates a tar.gz that the debug harness can extract with `run-as`.

The harness module is deliberately excluded from normal builds. See
tests/spikes/harness/README.md.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MANIFEST = os.path.join(ROOT, "runtime", "manifest.json")
DEFAULT_ARTIFACTS = os.path.join(ROOT, "runtime", "artifacts")
DEFAULT_STAGE = os.path.join(DEFAULT_ARTIFACTS, "spike")
DEFAULT_TAR = os.path.join(DEFAULT_ARTIFACTS, "spike-runtime.tar.gz")
DEFAULT_JNI = os.path.join(
    ROOT, "tests", "spikes", "harness", "src", "main", "jniLibs", "arm64-v8a"
)
APP_ID = "io.github.spectordev9.opencode.spike.debug"


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_pin(path: str, expected: str, label: str) -> None:
    if not os.path.isfile(path):
        fail(f"missing {label} archive: {path}")
    actual = sha256(path)
    if actual != expected:
        fail(f"{label} sha-256 mismatch: expected {expected}, got {actual}")


def run_fetch(force: bool) -> None:
    command = [sys.executable, os.path.join(ROOT, "scripts", "fetch-runtime.py")]
    command.extend(["--alpine", "--android-libs", "--alpine-runtime-libs"])
    if force:
        command.append("--force")
    print("fetching pinned runtime, Alpine rootfs and Android libraries")
    subprocess.run(command, cwd=ROOT, check=True)


def extract_deb(deb_path: str, wanted: tuple[str, ...], destination: str) -> dict[str, str]:
    """Extract the requested files from a Termux deb into destination."""
    with open(deb_path, "rb") as handle:
        if handle.read(8) != b"!<arch>\n":
            fail(f"not an ar archive: {deb_path}")
        data_archive = None
        while True:
            header = handle.read(60)
            if len(header) < 60:
                break
            name = header[:16].decode("utf-8", "replace").strip()
            if name.endswith("/"):
                name = name[:-1]
            size_field = header[48:58].decode("ascii", "replace").strip()
            try:
                size = int(size_field)
            except ValueError:
                fail(f"bad ar entry size in {deb_path}: {size_field!r}")
            payload = handle.read(size)
            if size % 2:
                handle.read(1)
            if name.startswith("data.tar"):
                data_archive = payload
                break
    if data_archive is None:
        fail(f"no data.tar entry in {deb_path}")

    with tempfile.TemporaryDirectory() as temp:
        with tarfile.open(fileobj=io.BytesIO(data_archive), mode="r:*") as archive:
            try:
                archive.extractall(temp, filter="fully_trusted")
            except TypeError:
                archive.extractall(temp)

        found: dict[str, str] = {}
        for base_name in wanted:
            matches = []
            for directory, _, files in os.walk(temp):
                for entry in files:
                    if entry == base_name or entry.startswith(base_name + "."):
                        candidate = os.path.join(directory, entry)
                        if os.path.isfile(candidate):
                            matches.append(candidate)
            if not matches:
                fail(f"{base_name} not found in {deb_path}")
            matches.sort(key=len)
            source = matches[0]
            if os.path.islink(source):
                link = os.readlink(source)
                if os.path.isabs(link):
                    source = os.path.join(temp, link.lstrip(os.sep))
                else:
                    source = os.path.join(os.path.dirname(source), link)
            target = os.path.join(destination, base_name)
            shutil.copy2(source, target)
            found[base_name] = target
    return found


def extract_alpine(archive_path: str, destination: str) -> None:
    os.makedirs(destination, exist_ok=True)
    with tarfile.open(archive_path, "r:gz") as archive:
        try:
            archive.extractall(destination, filter="fully_trusted")
        except TypeError:
            archive.extractall(destination)


def extract_alpine_runtime_package(archive_path: str, rootfs: str) -> None:
    os.makedirs(rootfs, exist_ok=True)
    root = os.path.realpath(rootfs)
    with tarfile.open(archive_path, "r:gz") as archive:
        members = []
        for member in archive.getmembers():
            name = member.name
            parts = name.split("/")
            if name.startswith("/") or ".." in parts:
                fail(f"unsafe path in Alpine runtime package: {name}")
            if name not in ("usr", "usr/lib") and not name.startswith("usr/lib/"):
                continue
            target = os.path.realpath(os.path.join(rootfs, name))
            if target != root and not target.startswith(root + os.sep):
                fail(f"Alpine runtime package escapes rootfs: {name}")
            if member.issym() or member.islnk():
                if os.path.isabs(member.linkname):
                    fail(f"absolute link in Alpine runtime package: {name}")
                resolved = os.path.realpath(
                    os.path.join(os.path.dirname(target), member.linkname)
                )
                if resolved != root and not resolved.startswith(root + os.sep):
                    fail(f"Alpine runtime package link escapes rootfs: {name}")
            members.append(member)
        archive.extractall(rootfs, members=members, filter="data")


def stage(manifest_path: str, artifacts: str, stage_dir: str, jni_dir: str) -> str:
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)

    version = manifest["version"]
    runtime_source = os.path.join(artifacts, f"opencode-{version}")
    runtime_archive = os.path.join(
        artifacts, f"opencode-linux-arm64-musl-{version}.tar.gz"
    )
    if not os.path.isdir(runtime_source):
        fail(f"runtime not found at {runtime_source}; run without --no-fetch first")
    verify_pin(runtime_archive, manifest["artifact"]["sha256"], "OpenCode")

    if os.path.isdir(stage_dir):
        shutil.rmtree(stage_dir)
    os.makedirs(stage_dir)

    runtime_destination = os.path.join(stage_dir, "runtime")
    os.makedirs(runtime_destination)
    for name in manifest["required"]:
        source = os.path.join(runtime_source, name)
        if not os.path.isfile(source) or os.path.getsize(source) == 0:
            fail(f"required runtime file missing or empty: {source}")
        shutil.copy2(source, os.path.join(runtime_destination, name))

    deb_files = {
        "proot": (
            os.path.join(artifacts, os.path.basename(manifest["proot"]["url"])),
            ("proot", "loader"),
        ),
        "libtalloc": (
            os.path.join(artifacts, os.path.basename(manifest["libtalloc"]["url"])),
            ("libtalloc.so.2",),
        ),
        "libandroid-shmem": (
            os.path.join(artifacts, os.path.basename(manifest["libandroid-shmem"]["url"])),
            ("libandroid-shmem.so",),
        ),
    }
    android_libs: dict[str, str] = {}
    for key, (deb_path, wanted) in deb_files.items():
        if not os.path.isfile(deb_path):
            fail(f"missing {deb_path}; rerun with --force or fetch --android-libs")
        verify_pin(deb_path, manifest[key]["sha256"], key)
        android_libs.update(extract_deb(deb_path, wanted, stage_dir))

    for name in ("proot", "loader", "libtalloc.so.2", "libandroid-shmem.so"):
        os.chmod(android_libs[name], 0o755)

    alpine = manifest["alpine"]
    alpine_archive = os.path.join(
        artifacts, f"alpine-minirootfs-{alpine['version']}-aarch64.tar.gz"
    )
    if not os.path.isfile(alpine_archive):
        fail(f"missing {alpine_archive}; rerun with --force")
    verify_pin(alpine_archive, alpine["sha256"], "Alpine minirootfs")
    rootfs = os.path.join(stage_dir, "rootfs")
    extract_alpine(alpine_archive, rootfs)

    for spec in manifest["alpineRuntimePackages"]:
        package_archive = os.path.join(artifacts, os.path.basename(spec["url"]))
        verify_pin(package_archive, spec["sha256"], spec["name"])
        extract_alpine_runtime_package(package_archive, rootfs)

    for name in manifest["runtimeLibraries"]:
        matches = []
        for directory, _, files in os.walk(rootfs):
            if name in files or os.path.islink(os.path.join(directory, name)):
                matches.append(os.path.join(directory, name))
        if len(matches) != 1 or not os.path.isfile(matches[0]):
            fail(f"expected exactly one Alpine runtime library {name}")
        shutil.copy2(matches[0], os.path.join(runtime_destination, name))

    os.makedirs(jni_dir, exist_ok=True)
    shutil.copy2(
        os.path.join(rootfs, manifest["loader"]),
        os.path.join(jni_dir, "libldmusl.so"),
    )
    shutil.copy2(os.path.join(stage_dir, "proot"), os.path.join(jni_dir, "libproot.so"))
    shutil.copy2(os.path.join(stage_dir, "loader"), os.path.join(jni_dir, "libproot_loader.so"))
    for name in ("libldmusl.so", "libproot.so", "libproot_loader.so"):
        os.chmod(os.path.join(jni_dir, name), 0o755)

    manifest_text = [
        "OpenCode Phase 2 spike runtime",
        f"opencode: {version} ({manifest['artifact']['sha256']})",
        f"alpine: {alpine['version']} ({alpine['sha256']})",
        f"proot: {manifest['proot']['version']} ({manifest['proot']['sha256']})",
        f"libtalloc: {manifest['libtalloc']['version']} ({manifest['libtalloc']['sha256']})",
        f"libandroid-shmem: {manifest['libandroid-shmem']['version']} "
        f"({manifest['libandroid-shmem']['sha256']})",
    ]
    with open(os.path.join(stage_dir, "MANIFEST.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(manifest_text) + "\n")
    return stage_dir


def create_archive(stage_dir: str, tar_path: str) -> None:
    os.makedirs(os.path.dirname(tar_path), exist_ok=True)
    partial = tar_path + ".part"
    with tarfile.open(partial, "w:gz") as archive:
        archive.add(stage_dir, arcname="spike")
    os.replace(partial, tar_path)
    digest = sha256(tar_path)
    with open(tar_path + ".sha256", "w", encoding="utf-8") as handle:
        handle.write(f"{digest}  {os.path.basename(tar_path)}\n")
    print(f"wrote {tar_path}")
    print(f"sha-256 {digest}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--artifacts", default=DEFAULT_ARTIFACTS)
    parser.add_argument("--stage-dir", default=DEFAULT_STAGE)
    parser.add_argument("--tar", default=DEFAULT_TAR)
    parser.add_argument("--jni-dir", default=DEFAULT_JNI)
    parser.add_argument("--no-fetch", action="store_true", help="use already fetched artifacts")
    parser.add_argument("--force", action="store_true", help="redownload artifacts")
    args = parser.parse_args()

    if not args.no_fetch:
        run_fetch(args.force)

    stage(args.manifest, args.artifacts, args.stage_dir, args.jni_dir)
    create_archive(args.stage_dir, args.tar)

    print()
    print("Build the isolated harness:")
    print("  ./gradlew -PspikeHarness=true :spike-harness:assembleDebug")
    print("Install it:")
    print(
        "  adb install -r "
        "tests/spikes/harness/build/outputs/apk/debug/spike-harness-debug.apk"
    )
    print("Stage the runtime into app-private storage:")
    print(f"  adb push {args.tar} /data/local/tmp/")
    print(
        f"  adb shell run-as {APP_ID} sh -c "
        f"'cd files && tar xzf /data/local/tmp/{os.path.basename(args.tar)}'"
    )
    print("Then open the harness, run the probes and pull the JSON report:")
    print(f"  adb shell run-as {APP_ID} ls files/reports")
    print(f"  adb shell run-as {APP_ID} cat files/reports/<report>.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
