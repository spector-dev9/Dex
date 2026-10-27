#!/usr/bin/env python3
"""Validate the runtime manifest shipped in the Android app assets.

The app asset manifest must stay consistent with the pinned runtime manifest
used by the installers and by CI (RT-3). This checker fails on invalid JSON,
drift in the pinned artifact identity, or a placeholder hash.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_APP = ROOT / "app/src/main/assets/manifest.json"
DEFAULT_RUNTIME = ROOT / "runtime/manifest.json"
FIELDS = ("version", "profile", "binary", "binarySha256")
PLACEHOLDER = "0" * 64


def check(app_path: Path, runtime_path: Path) -> list[str]:
    errors: list[str] = []
    try:
        app = json.loads(app_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        return [f"{app_path}: cannot read app manifest: {failure}"]
    try:
        runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        return [f"{runtime_path}: cannot read runtime manifest: {failure}"]

    for field in FIELDS:
        if field not in app:
            errors.append(f"app manifest is missing {field}")
            continue
        expected = runtime.get(field)
        if expected is None:
            errors.append(f"runtime manifest is missing {field}")
            continue
        if app[field] != expected:
            errors.append(
                f"app manifest {field} drift: app={app[field]!r} runtime={expected!r}"
            )

    digest = app.get("binarySha256")
    if isinstance(digest, str):
        if digest == PLACEHOLDER:
            errors.append("app manifest binarySha256 is still the all-zero placeholder")
        elif len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest.lower()):
            errors.append("app manifest binarySha256 is not a lowercase or uppercase SHA-256")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, default=DEFAULT_APP)
    parser.add_argument("--runtime", type=Path, default=DEFAULT_RUNTIME)
    args = parser.parse_args()

    errors = check(args.app, args.runtime)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"app runtime manifest matches the pinned runtime manifest: {args.app}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
