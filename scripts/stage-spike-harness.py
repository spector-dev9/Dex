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
import posixpath
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
DEFAULT_RUNTIME_ASSETS = os.path.join(
    ROOT, "tests", "spikes", "harness", "src", "main", "assets", "runtime-libs"
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
    command.extend(["--alpine", "--android-libs", "--alpine-runtime-libs", "--alpine-node"])
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


def record_mode(modes: dict[str, int], path: str, mode: int) -> None:
    """Remember an archive member's mode for the device archive.

    Windows filesystem modes cannot represent the Alpine permission bits,
    so the archive assembly overrides modes from this table.
    """
    modes[os.path.abspath(path)] = mode & 0o7777


def posix_member_path(name: str) -> str:
    """Normalize an archive member name to a relative posix path."""
    cleaned = posixpath.normpath(name)
    if cleaned in (".", "/"):
        return ""
    if cleaned.startswith(("/", "../")) or cleaned == "..":
        fail(f"unsafe path in archive: {name!r}")
    return cleaned


def extract_archive(
    archive_path: str,
    destination: str,
    *,
    label: str,
    symlinks: dict[str, str],
    modes: dict[str, int],
) -> None:
    """Extract an Alpine archive member by member, without resolving links.

    ``TarFile.extractall`` resolves each link target recursively and can hit
    ``RecursionError`` on link-heavy Alpine archives (observed with Python
    3.13 on Windows). Creating symbolic links on Windows also needs a
    privilege the build user usually lacks. This extractor materializes
    directories, regular files and hard links, and records symbolic links in
    ``symlinks`` (rootfs-relative path -> link target) so that the archive
    assembly can recreate them for the device.
    """
    os.makedirs(destination, exist_ok=True)
    root = os.path.realpath(destination)
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive:
            name = posix_member_path(member.name)
            if not name:
                if member.isdir():
                    continue
                fail(f"unexpected root member in {label}: {member.name!r}")
            if "\\" in name:
                fail(f"backslash path in {label}: {member.name!r}")
            target = os.path.abspath(os.path.join(destination, *name.split("/")))
            if target != root and not target.startswith(root + os.sep):
                fail(f"{label} member escapes the destination: {member.name!r}")
            if member.isdir():
                os.makedirs(target, exist_ok=True)
                record_mode(modes, target, member.mode)
            elif member.isfile():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    fail(f"unreadable member in {label}: {member.name!r}")
                with source, open(target, "wb") as output:
                    shutil.copyfileobj(source, output)
                record_mode(modes, target, member.mode)
                symlinks.pop(name, None)
            elif member.issym():
                if os.path.lexists(target):
                    if os.path.isdir(target):
                        fail(f"{label} symlink conflicts with a directory: {member.name!r}")
                    os.remove(target)
                symlinks[name] = member.linkname
            elif member.islnk():
                link_target = os.path.abspath(
                    os.path.join(destination, *member.linkname.split("/"))
                )
                if link_target != root and not link_target.startswith(root + os.sep):
                    fail(f"{label} hard link escapes the destination: {member.name!r}")
                os.makedirs(os.path.dirname(target), exist_ok=True)
                try:
                    os.link(link_target, target)
                except OSError as error:
                    fail(f"hard link failed in {label}: {member.name!r}: {error}")
                record_mode(modes, target, member.mode or modes.get(link_target, 0o644))
            else:
                fail(
                    f"unsupported special file in {label}: {member.name!r} "
                    f"(type {member.type!r})"
                )


def resolve_recorded_path(
    guest_path: str,
    *,
    rootfs: str,
    symlinks: dict[str, str],
    label: str,
) -> str:
    """Resolve a rootfs-relative path through recorded links to a host path."""
    current = posixpath.normpath(guest_path.lstrip("/"))
    seen: set[str] = set()
    while current in symlinks:
        if current in seen:
            fail(f"symbolic link loop resolving {label}: {guest_path}")
        seen.add(current)
        link_target = symlinks[current]
        if link_target.startswith("/"):
            current = posixpath.normpath(link_target.lstrip("/"))
        else:
            current = posixpath.normpath(
                posixpath.join(posixpath.dirname(current), link_target)
            )
        if current == ".." or current.startswith("../"):
            fail(f"symbolic link escapes the rootfs resolving {label}: {guest_path}")
    return os.path.join(rootfs, *current.split("/"))


def stage(
    manifest_path: str,
    artifacts: str,
    stage_dir: str,
    jni_dir: str,
    runtime_assets_dir: str,
    modes: dict[str, int],
    symlinks: dict[str, str],
) -> str:
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
        if name == manifest["binary"]:
            verify_pin(source, manifest["binarySha256"], "OpenCode executable")
        destination = os.path.join(runtime_destination, name)
        shutil.copy2(source, destination)
        record_mode(modes, destination, 0o755 if name == manifest["binary"] else 0o644)

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
        record_mode(modes, android_libs[name], 0o755)

    alpine = manifest["alpine"]
    alpine_archive = os.path.join(
        artifacts, f"alpine-minirootfs-{alpine['version']}-aarch64.tar.gz"
    )
    if not os.path.isfile(alpine_archive):
        fail(f"missing {alpine_archive}; rerun with --force")
    verify_pin(alpine_archive, alpine["sha256"], "Alpine minirootfs")
    rootfs = os.path.join(stage_dir, "rootfs")
    extract_archive(
        alpine_archive,
        rootfs,
        label="Alpine minirootfs",
        symlinks=symlinks,
        modes=modes,
    )

    for spec in manifest["alpineRuntimePackages"]:
        package_archive = os.path.join(artifacts, os.path.basename(spec["url"]))
        verify_pin(package_archive, spec["sha256"], spec["name"])
        extract_archive(
            package_archive,
            rootfs,
            label=spec["name"],
            symlinks=symlinks,
            modes=modes,
        )

    node_runtime = manifest["nodeRuntime"]
    for spec in node_runtime["packages"]:
        package_archive = os.path.join(artifacts, os.path.basename(spec["url"]))
        verify_pin(package_archive, spec["sha256"], spec["name"])
        extract_archive(
            package_archive,
            rootfs,
            label=spec["name"],
            symlinks=symlinks,
            modes=modes,
        )
    node_path = os.path.join(rootfs, node_runtime["executable"])
    if not os.path.isfile(node_path):
        fail(f"pinned Alpine Node package did not install {node_runtime['executable']}")
    os.chmod(node_path, os.stat(node_path).st_mode | 0o111)

    for name in manifest["runtimeLibraries"]:
        candidates: list[str] = []
        for directory, _directories, files in os.walk(rootfs):
            if name in files:
                relative = os.path.relpath(os.path.join(directory, name), rootfs)
                candidates.append(relative.replace(os.sep, "/"))
        for guest in symlinks:
            if posixpath.basename(guest) == name:
                candidates.append(guest)
        if len(candidates) != 1:
            fail(f"expected exactly one Alpine runtime library {name}: {sorted(candidates)}")
        source = resolve_recorded_path(
            candidates[0],
            rootfs=rootfs,
            symlinks=symlinks,
            label=name,
        )
        if not os.path.isfile(source):
            fail(f"Alpine runtime library {name} does not resolve to a file: {source}")
        destination = os.path.join(runtime_destination, name)
        shutil.copy2(source, destination)
        record_mode(modes, destination, modes.get(os.path.abspath(source), 0o644))

    loader_source = resolve_recorded_path(
        manifest["loader"],
        rootfs=rootfs,
        symlinks=symlinks,
        label="musl loader",
    )
    if not os.path.isfile(loader_source):
        fail(f"Alpine loader not found in the rootfs: {manifest['loader']}")
    os.makedirs(jni_dir, exist_ok=True)
    shutil.copy2(loader_source, os.path.join(jni_dir, "libldmusl.so"))
    shutil.copy2(os.path.join(stage_dir, "proot"), os.path.join(jni_dir, "libproot.so"))
    shutil.copy2(os.path.join(stage_dir, "loader"), os.path.join(jni_dir, "libproot_loader.so"))
    for name in ("libldmusl.so", "libproot.so", "libproot_loader.so"):
        os.chmod(os.path.join(jni_dir, name), 0o755)

    os.makedirs(runtime_assets_dir, exist_ok=True)
    for name in ("libtalloc.so.2", "libandroid-shmem.so"):
        shutil.copy2(android_libs[name], os.path.join(runtime_assets_dir, name))

    manifest_text = [
        "OpenCode Phase 2 spike runtime",
        f"opencode: {version} ({manifest['artifact']['sha256']})",
        f"alpine: {alpine['version']} ({alpine['sha256']})",
        f"node: {node_runtime['version']} {node_runtime['architecture']}-{node_runtime['libc']}",
        f"proot: {manifest['proot']['version']} ({manifest['proot']['sha256']})",
        f"libtalloc: {manifest['libtalloc']['version']} ({manifest['libtalloc']['sha256']})",
        f"libandroid-shmem: {manifest['libandroid-shmem']['version']} "
        f"({manifest['libandroid-shmem']['sha256']})",
    ]
    with open(os.path.join(stage_dir, "MANIFEST.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(manifest_text) + "\n")
    return stage_dir


def create_archive(
    stage_dir: str,
    tar_path: str,
    modes: dict[str, int],
    symlinks: dict[str, str],
) -> None:
    os.makedirs(os.path.dirname(tar_path), exist_ok=True)
    partial = tar_path + ".part"

    def apply_mode(
        tarinfo: tarfile.TarInfo,
        path: str | None = None,
    ) -> tarfile.TarInfo:
        name = tarinfo.name.replace("\\", "/")
        if name.startswith("spike/"):
            name = name[len("spike/"):]
        recorded = modes.get(
            os.path.abspath(os.path.join(stage_dir, *name.split("/")))
        )
        if recorded is not None:
            tarinfo.mode = recorded
        elif tarinfo.isdir():
            tarinfo.mode = 0o755
        elif tarinfo.isfile():
            tarinfo.mode = 0o644
        return tarinfo

    with tarfile.open(partial, "w:gz") as archive:
        archive.add(stage_dir, arcname="spike", filter=apply_mode)
        # Symbolic links cannot be created on Windows without extra
        # privileges, so the recorded members are recreated here, after the
        # staged tree. The device extraction creates them once their parent
        # directories exist; nothing extracts through a link.
        for guest, linkname in sorted(symlinks.items()):
            info = tarfile.TarInfo(f"spike/rootfs/{guest}")
            info.type = tarfile.SYMTYPE
            info.linkname = linkname
            info.mode = 0o777
            archive.addfile(info)
    os.replace(partial, tar_path)
    digest = sha256(tar_path)
    with open(tar_path + ".sha256", "w", encoding="utf-8") as handle:
        handle.write(f"{digest}  {os.path.basename(tar_path)}\n")
    print(f"wrote {tar_path}")
    print(f"sha-256 {digest}")
    print(f"recreated {len(symlinks)} symbolic link members")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--artifacts", default=DEFAULT_ARTIFACTS)
    parser.add_argument("--stage-dir", default=DEFAULT_STAGE)
    parser.add_argument("--tar", default=DEFAULT_TAR)
    parser.add_argument("--jni-dir", default=DEFAULT_JNI)
    parser.add_argument("--runtime-assets-dir", default=DEFAULT_RUNTIME_ASSETS)
    parser.add_argument("--no-fetch", action="store_true", help="use already fetched artifacts")
    parser.add_argument("--force", action="store_true", help="redownload artifacts")
    args = parser.parse_args()

    if not args.no_fetch:
        run_fetch(args.force)

    modes: dict[str, int] = {}
    symlinks: dict[str, str] = {}
    stage(
        args.manifest,
        args.artifacts,
        args.stage_dir,
        args.jni_dir,
        args.runtime_assets_dir,
        modes,
        symlinks,
    )
    create_archive(args.stage_dir, args.tar, modes, symlinks)

    print()
    print("Build the isolated harness:")
    print("  ./gradlew -PspikeHarness=true :spike-harness:assembleDebug")
    print("Install it:")
    print(
        "  adb install -r "
        "tests/spikes/harness/build/outputs/apk/debug/spike-harness-debug.apk"
    )
    print("Open the harness and tap Download and verify pinned runtime.")
    print("Then run the probes and pull the JSON report:")
    print(f"  adb shell run-as {APP_ID} ls files/reports")
    print(f"  adb shell run-as {APP_ID} cat files/reports/<report>.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
