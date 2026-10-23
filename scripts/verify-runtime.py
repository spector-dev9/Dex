#!/usr/bin/env python3
"""Verify a fetched runtime: required files and ELF LOAD alignment.

Phase 2 spike tooling (RT-3, RT-5 groundwork). Uses llvm-readelf when
available, otherwise readelf.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MANIFEST = os.path.join(ROOT, "runtime", "manifest.json")
DEFAULT_DEST = os.path.join(ROOT, "runtime", "artifacts")


def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_alignment(readelf: str, path: str) -> list[int]:
    output = subprocess.run(
        [readelf, "-l", path], capture_output=True, text=True, check=False
    )
    if output.returncode != 0:
        return []
    alignments = []
    for line in output.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] == "LOAD":
            try:
                alignments.append(int(fields[-1], 16))
            except ValueError:
                continue
    return alignments


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--dest", default=DEFAULT_DEST)
    args = parser.parse_args()

    with open(args.manifest, encoding="utf-8") as handle:
        manifest = json.load(handle)

    runtime = os.path.join(args.dest, f"opencode-{manifest['version']}")
    if not os.path.isdir(runtime):
        fail(f"runtime not found at {runtime}; run scripts/fetch-runtime.py first")

    readelf = shutil.which("llvm-readelf") or shutil.which("readelf")
    if not readelf:
        fail("no readelf or llvm-readelf available")

    report = {"runtimeDir": runtime, "files": [], "minimumAlignment": None, "pass": True}

    for name in manifest["required"]:
        path = os.path.join(runtime, name)
        entry = {"name": name, "present": os.path.isfile(path), "size": None, "loadAlign": []}
        if entry["present"]:
            entry["size"] = os.path.getsize(path)
            if entry["size"] == 0:
                entry["present"] = False
        if entry["present"] and name != "libstdc++.so.6.runtime":
            entry["loadAlign"] = load_alignment(readelf, path)
            if entry["loadAlign"]:
                minimum = min(entry["loadAlign"])
                report["minimumAlignment"] = (
                    minimum
                    if report["minimumAlignment"] is None
                    else min(report["minimumAlignment"], minimum)
                )
                if minimum < 16384:
                    report["pass"] = False
        if not entry["present"]:
            report["pass"] = False
        report["files"].append(entry)

    print(json.dumps(report, indent=2))
    return 0 if report["pass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
