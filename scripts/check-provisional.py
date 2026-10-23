#!/usr/bin/env python3
"""Verify G0-gated members are annotated in compiled contract classes."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "build" / "reports" / "g1" / "provisional-members.txt"
REQUIRED = (
    ("io.github.spectordev9.opencode.core.runtime.RuntimeManager", "spawn"),
    ("io.github.spectordev9.opencode.core.terminal.PtyService", None),
    ("io.github.spectordev9.opencode.core.supervisor.SupervisorConfig", None),
)


def class_roots() -> list[Path]:
    return sorted(
        path
        for path in ROOT.glob("core/*-api/build/classes/kotlin/main")
        if path.is_dir()
    )


def main() -> int:
    roots = class_roots()
    if not roots:
        print("error: no compiled core classes; run ./gradlew coreCheck", file=sys.stderr)
        return 1

    classpath = ":".join(str(root) for root in roots)
    lines = [
        "# G1 provisional members (compiled classes)",
        "",
        "Annotation: io.github.spectordev9.opencode.core.common.RequiresG0",
        "",
    ]
    errors: list[str] = []

    for class_name, member in REQUIRED:
        result = subprocess.run(
            ["javap", "-v", "-p", "-classpath", classpath, class_name],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            errors.append(f"{class_name}: not found in compiled classes")
            continue
        if "io.github.spectordev9.opencode.core.common.RequiresG0" not in result.stdout:
            errors.append(f"{class_name}: missing @RequiresG0")
            continue
        if member is not None and member not in result.stdout:
            errors.append(f"{class_name}.{member}: member not found")
            continue
        label = class_name if member is None else f"{class_name}.{member}"
        lines.append(f"annotated: {label}")

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"provisional members: {len(REQUIRED)} annotated, report {REPORT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
