#!/usr/bin/env python3
"""Build the APK-native Profile A agent-shell wrapper.

OpenCode executes the configured shell directly, so the wrapper must live in
the read-only native-library directory. This script cross-compiles the small
C helper with the same NDK used by the Android build.
"""

from __future__ import annotations

import argparse
import os
import subprocess


def sdk_candidates() -> list[str]:
    candidates = [
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.environ.get("ANDROID_HOME", ""),
        os.path.expanduser("~/Android/Sdk"),
    ]
    local_properties = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "local.properties",
    )
    if os.path.isfile(local_properties):
        with open(local_properties, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("sdk.dir="):
                    candidates.append(line.split("=", 1)[1].strip())
                    break
    return [candidate for candidate in candidates if candidate]


def find_ndk(explicit: str | None) -> str:
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(name)
        if value:
            candidates.append(value)
    for sdk in sdk_candidates():
        ndk_root = os.path.join(sdk, "ndk")
        if not os.path.isdir(ndk_root):
            continue
        versions = [
            os.path.join(ndk_root, name)
            for name in os.listdir(ndk_root)
            if os.path.isdir(os.path.join(ndk_root, name))
        ]
        versions.sort(key=lambda path: os.path.basename(path), reverse=True)
        candidates.extend(versions)

    for candidate in candidates:
        if os.path.isfile(os.path.join(candidate, "ndk-build")):
            return candidate
    raise SystemExit(
        "error: Android NDK not found; install the NDK or set ANDROID_NDK_HOME"
    )


def find_clang(ndk: str, api: int) -> str:
    prebuilt = os.path.join(ndk, "toolchains", "llvm", "prebuilt")
    if not os.path.isdir(prebuilt):
        raise SystemExit(f"error: NDK LLVM toolchain is missing under {prebuilt}")
    hosts = sorted(os.listdir(prebuilt))
    if not hosts:
        raise SystemExit(f"error: NDK LLVM prebuilt host is missing under {prebuilt}")
    clang = os.path.join(
        prebuilt,
        hosts[0],
        "bin",
        f"aarch64-linux-android{api}-clang",
    )
    if not os.path.isfile(clang):
        raise SystemExit(f"error: NDK clang not found at {clang}")
    return clang


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--ndk", default=None)
    parser.add_argument("--api", type=int, default=26)
    args = parser.parse_args()

    if not os.path.isfile(args.source):
        raise SystemExit(f"error: wrapper source not found: {args.source}")

    ndk = find_ndk(args.ndk)
    clang = find_clang(ndk, args.api)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)

    command = [
        clang,
        "-O2",
        "-fPIE",
        "-pie",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wl,-z,max-page-size=16384",
        "-o",
        args.output,
        args.source,
    ]
    print("building guest shell wrapper:", " ".join(command))
    subprocess.run(command, check=True)
    os.chmod(args.output, 0o755)

    with open(args.output, "rb") as handle:
        if handle.read(4) != b"\x7fELF":
            raise SystemExit(f"error: wrapper is not an ELF executable: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
