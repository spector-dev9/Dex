#!/usr/bin/env python3
"""Validate the G3 acceptance-status tracker.

Every section-7 acceptance criterion from docs/spec.md must be present exactly
once, use an allowed status, and cite evidence that exists in this repository
(or a CI run / URL). This keeps the G3 evidence report honest and complete as
the device and app wiring land.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_STATUS = ROOT / "docs/evidence/g3/status.json"

EXPECTED = (
    [f"RT-{index}" for index in range(1, 6)]
    + [f"STR-{index}" for index in range(1, 6)]
    + [f"WS-{index}" for index in range(1, 7)]
    + [f"TERM-{index}" for index in range(1, 6)]
    + [f"LC-{index}" for index in range(1, 7)]
    + [f"SEC-{index}" for index in range(1, 7)]
    + [f"REL-{index}" for index in range(1, 5)]
    + ["QA-1", "QA-2"]
)
ALLOWED = {"PASS", "PARTIAL", "PENDING", "BLOCKED", "DEFERRED", "OUT_OF_SCOPE"}
DEFERRABLE = {"LC-5", "LC-6"}


def evidence_exists(reference: str) -> bool:
    if reference.startswith(("ci:", "http://", "https://")):
        return True
    return (ROOT / reference).exists()


def check(path: Path) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    counts: dict[str, int] = {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        return [f"{path}: cannot read status tracker: {failure}"], counts

    if payload.get("gate") != "G3":
        errors.append("tracker gate must be 'G3'")
    if not payload.get("reference_device"):
        errors.append("tracker must record the reference device")

    entries = payload.get("criteria")
    if not isinstance(entries, list):
        return errors + ["tracker must contain a criteria list"], counts

    seen: dict[str, dict] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(f"criteria entry is not an object: {entry!r}")
            continue
        identifier = entry.get("id")
        if identifier in seen:
            errors.append(f"duplicate criterion: {identifier}")
        seen[identifier] = entry

    missing = [identifier for identifier in EXPECTED if identifier not in seen]
    extra = [identifier for identifier in seen if identifier not in EXPECTED]
    if missing:
        errors.append(f"missing criteria: {', '.join(missing)}")
    if extra:
        errors.append(f"unknown criteria: {', '.join(extra)}")

    for identifier in EXPECTED:
        entry = seen.get(identifier)
        if entry is None:
            continue
        status = entry.get("status")
        counts[status] = counts.get(status, 0) + 1
        if status not in ALLOWED:
            errors.append(f"{identifier}: invalid status {status!r}")
            continue
        if identifier in DEFERRABLE and status not in {"DEFERRED", "PASS"}:
            errors.append(f"{identifier}: optional post-v1 check must be DEFERRED or PASS")
        if not entry.get("notes"):
            errors.append(f"{identifier}: missing notes")
        evidence = entry.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"{identifier}: must cite at least one evidence item")
            continue
        for reference in evidence:
            if not isinstance(reference, str) or not evidence_exists(reference):
                errors.append(f"{identifier}: evidence does not exist: {reference!r}")

    return errors, counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", type=Path, default=DEFAULT_STATUS)
    args = parser.parse_args()

    errors, counts = check(args.status)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1

    summary = ", ".join(f"{status}={counts[status]}" for status in sorted(counts))
    print(f"G3 status tracker valid: {len(EXPECTED)} criteria ({summary})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
