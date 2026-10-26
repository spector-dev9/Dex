#!/usr/bin/env python3
"""Run the repository checks that can be automated for the G0 gate.

Passing this runner does not unblock G0 by itself. Physical-device evidence,
external artifacts, owner decisions, and acceptance criteria are reported as
remaining gates after the local checks finish.
"""

from __future__ import annotations

import argparse
import glob
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Check:
    name: str
    command: tuple[str, ...]
    cwd: Path = ROOT
    executable: str | None = None


CHECKS = (
    Check("Bridge typecheck", ("npm", "run", "typecheck"), executable="npm"),
    Check("Contract and bridge tests", ("npm", "test"), executable="npm"),
    Check("Python syntax", ("__PY_COMPILE__",)),
    Check("Architecture boundaries", ("python3", "scripts/check-architecture.py"), executable="python3"),
    Check("Kotlin bridge contract parity", ("python3", "scripts/check-bridge-contract.py"), executable="python3"),
    Check("License artifacts", ("python3", "scripts/check-licenses.py"), executable="python3"),
    Check(
        "License text checksums",
        ("sha256sum", "-c", "SHA256SUMS"),
        cwd=ROOT / "docs/licenses/texts",
        executable="sha256sum",
    ),
    Check(
        "OpenAPI contract drift",
        ("python3", "scripts/check-contract-drift.py", "--doc", "contract/openapi/opencode-1.19.3.json"),
        executable="python3",
    ),
    Check("Core Kotlin contracts", ("./gradlew", "--no-daemon", "-PskipApp=true", "coreCheck")),
    Check("Provisional contract annotations", ("python3", "scripts/check-provisional.py"), executable="python3"),
    Check("Android debug and release APKs", ("./gradlew", "--no-daemon", ":app:assembleDebug", ":app:assembleRelease")),
    Check("Release manifest unit tests", ("python3", "tests/security/test_release_manifest.py"), executable="python3"),
    Check(
        "Merged release manifest policy",
        (
            "python3",
            "scripts/check-release-manifest.py",
            "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
        ),
        executable="python3",
    ),
    Check("Stage isolated harness runtime", ("python3", "scripts/stage-spike-harness.py"), executable="python3"),
    Check(
        "Build isolated Android harness",
        ("./gradlew", "--no-daemon", "-PspikeHarness=true", "-PskipApp=true", ":spike-harness:assembleDebug"),
    ),
)

HOST_ONLY_CHECKS = CHECKS[:10]

G0_BLOCKERS = (
    "S1: complete Profile A/B and Tier A/B/C physical-device matrix, including a 16 KB-page device",
    "S2: Profile A/B performance comparison; Profile B is not implemented",
    "S3: compare the production agent shell with the PTY on target devices",
    "S6: evaluate a distributable OpenCode 2.x artifact when one exists",
    "S7/LC-2: real active-agent, screen-off 30-minute runs; at least 19/20 per required tier, or accepted OEM guidance",
    "S8/TERM-5: native terminal UI with real IME input and app-provided control keys",
    "S9: complete licensing, dependency-closure, source-offer, and release-compliance evidence",
    "G0 decisions: ratify thresholds and freeze D1, D4, and D5 from admissible evidence",
)


def print_plan(host_only: bool) -> None:
    checks = HOST_ONLY_CHECKS if host_only else CHECKS
    print("G0 automated check plan:")
    for index, check in enumerate(checks, start=1):
        command = "Python compile for repository scripts" if check.command[0] == "__PY_COMPILE__" else " ".join(check.command)
        print(f"  {index}. {check.name}: {command}")
    print("Not executable by this runner (must remain evidence/decision gates):")
    for blocker in G0_BLOCKERS:
        print(f"  - {blocker}")
    print("A passing local check suite does not mark G0 complete.")


def run_check(check: Check) -> str:
    if check.executable and shutil.which(check.executable) is None:
        print(f"\nBLOCKED: {check.name}: missing executable `{check.executable}`")
        return "BLOCKED"

    if check.command[0] == "__PY_COMPILE__":
        paths = sorted(
            glob.glob("scripts/*.py", root_dir=ROOT)
            + glob.glob("tests/spikes/scripts/*.py", root_dir=ROOT)
        )
        command: Sequence[str] = (sys.executable, "-m", "py_compile", *paths)
    else:
        command = check.command

    if check.name == "Merged release manifest policy":
        manifest = ROOT / check.command[-1]
        if not manifest.is_file():
            print(f"\nBLOCKED: {check.name}: merged manifest not found; build release APK first")
            return "BLOCKED"

    print(f"\n=== {check.name} ===", flush=True)
    result = subprocess.run(command, cwd=check.cwd, check=False)
    status = "PASS" if result.returncode == 0 else "FAIL"
    print(f"{status}: {check.name} (exit {result.returncode})")
    return status


def run_inventory_check() -> str:
    diff = subprocess.run(
        ("git", "diff", "--quiet", "--", "contract/endpoints.json"),
        cwd=ROOT,
        check=False,
    )
    if diff.returncode != 0:
        print("\nBLOCKED: Inventory reproducibility: contract/endpoints.json has local edits")
        return "BLOCKED"
    if shutil.which("python3") is None:
        print("\nBLOCKED: Inventory reproducibility: missing executable `python3`")
        return "BLOCKED"

    print("\n=== Inventory reproducibility ===", flush=True)
    generated = subprocess.run(("python3", "scripts/build-inventory.py"), cwd=ROOT, check=False)
    if generated.returncode != 0:
        print(f"FAIL: Inventory generation (exit {generated.returncode})")
        return "FAIL"
    clean = subprocess.run(
        ("git", "diff", "--exit-code", "--", "contract/endpoints.json"),
        cwd=ROOT,
        check=False,
    )
    status = "PASS" if clean.returncode == 0 else "FAIL"
    print(f"{status}: Inventory reproducibility")
    return status


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--host-only",
        action="store_true",
        help="run contract, Python, and core Kotlin checks only; skip Android builds and runtime staging",
    )
    parser.add_argument("--list", action="store_true", help="list automated checks and non-automated G0 gates")
    args = parser.parse_args()

    if args.list:
        print_plan(args.host_only)
        return 0

    checks = HOST_ONLY_CHECKS if args.host_only else CHECKS
    outcomes: list[tuple[str, str]] = []
    core_status = "NOT_RUN"
    for check in checks:
        if check.name == "Provisional contract annotations" and core_status != "PASS":
            print("\nBLOCKED: Provisional contract annotations: coreCheck did not pass")
            outcomes.append((check.name, "BLOCKED"))
            continue
        outcomes.append((check.name, run_check(check)))
        if check.name == "Core Kotlin contracts":
            core_status = outcomes[-1][1]
        if check.name == "OpenAPI contract drift":
            outcomes.append(("Inventory reproducibility", run_inventory_check()))

    passed = sum(status == "PASS" for _, status in outcomes)
    failed = sum(status == "FAIL" for _, status in outcomes)
    blocked = sum(status == "BLOCKED" for _, status in outcomes)
    print(f"\nAutomated checks: {passed} passed, {failed} failed, {blocked} blocked.")
    print("G0 remains pending until all listed physical evidence and owner decisions are accepted.")
    for blocker in G0_BLOCKERS:
        print(f"  BLOCKED: {blocker}")
    if failed or blocked:
        return 1
    return 2 if G0_BLOCKERS else 0


if __name__ == "__main__":
    raise SystemExit(main())
