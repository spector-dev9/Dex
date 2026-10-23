#!/usr/bin/env python3
"""Compare a candidate OpenCode /doc with the pinned contract.

S6 fallback (oc.md): keep the 1.x pin and run a drift job. A candidate
fails only when an endpoint the app uses disappears or gains a required
input. Other additions/changes are reported for review.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PINNED = os.path.join(ROOT, "contract", "openapi", "opencode-1.19.3.json")
INVENTORY = os.path.join(ROOT, "contract", "endpoints.json")


def load(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def operation(doc: dict, path: str, method: str) -> dict | None:
    return doc.get("paths", {}).get(path, {}).get(method.lower())


def parameters(op: dict) -> dict[str, bool]:
    result: dict[str, bool] = {}
    for parameter in op.get("parameters", []):
        if not isinstance(parameter, dict):
            continue
        name = parameter.get("name")
        location = parameter.get("in")
        if isinstance(name, str) and isinstance(location, str):
            result[f"{location}:{name}"] = bool(parameter.get("required"))
    return result


def success_codes(op: dict) -> set[str]:
    return {
        str(code)
        for code in op.get("responses", {})
        if str(code).isdigit() and 200 <= int(code) < 300
    }


def compare(candidate: dict, pinned: dict, inventory: dict) -> tuple[list[str], list[str]]:
    breaking: list[str] = []
    warnings: list[str] = []

    for entry in inventory.get("required", []):
        method = entry["method"]
        path = entry["path"]
        label = f"{method} {path}"
        candidate_op = operation(candidate, path, method)
        pinned_op = operation(pinned, path, method)
        if candidate_op is None:
            breaking.append(f"missing endpoint: {label}")
            continue
        if pinned_op is None:
            warnings.append(f"pinned endpoint was already missing: {label}")
            continue

        candidate_params = parameters(candidate_op)
        pinned_params = parameters(pinned_op)
        for key, required in candidate_params.items():
            if key not in pinned_params and required:
                breaking.append(f"new required parameter on {label}: {key}")
            elif key not in pinned_params:
                warnings.append(f"new optional parameter on {label}: {key}")
        for key in pinned_params:
            if key not in candidate_params:
                warnings.append(f"removed parameter from {label}: {key}")

        candidate_body = candidate_op.get("requestBody")
        pinned_body = pinned_op.get("requestBody")
        if isinstance(candidate_body, dict) and not isinstance(pinned_body, dict):
            if candidate_body.get("required"):
                breaking.append(f"new required request body on {label}")
            else:
                warnings.append(f"new optional request body on {label}")
        elif isinstance(candidate_body, dict) and isinstance(pinned_body, dict):
            if candidate_body.get("required") and not pinned_body.get("required"):
                breaking.append(f"request body became required on {label}")

        candidate_codes = success_codes(candidate_op)
        pinned_codes = success_codes(pinned_op)
        if pinned_codes and not (candidate_codes & pinned_codes):
            warnings.append(
                f"success status changed on {label}: "
                f"{sorted(pinned_codes)} -> {sorted(candidate_codes)}"
            )

    candidate_paths = set(candidate.get("paths", {}))
    pinned_paths = set(pinned.get("paths", {}))
    added = sorted(candidate_paths - pinned_paths)
    removed = sorted(pinned_paths - candidate_paths)
    if added:
        warnings.append(f"new paths ({len(added)}): {', '.join(added[:12])}")
    if removed:
        warnings.append(f"removed paths ({len(removed)}): {', '.join(removed[:12])}")
    return breaking, warnings


def render(candidate: dict, candidate_path: str, breaking: list[str], warnings: list[str]) -> str:
    version = candidate.get("info", {}).get("version", "unknown")
    lines = [
        "# OpenCode contract drift",
        "",
        f"- Candidate: `{candidate_path}`",
        f"- Reported version: `{version}`",
        f"- OpenAPI: `{candidate.get('openapi', 'unknown')}`",
        f"- Paths: `{len(candidate.get('paths', {}))}`",
        f"- Breaking findings: `{len(breaking)}`",
        f"- Warnings: `{len(warnings)}`",
        "",
        "## Breaking",
        "",
    ]
    lines.extend(f"- {item}" for item in breaking) if breaking else lines.append("- none")
    lines += ["", "## Warnings", ""]
    lines.extend(f"- {item}" for item in warnings) if warnings else lines.append("- none")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--doc", required=True, help="candidate /doc JSON")
    parser.add_argument("--pinned", default=PINNED)
    parser.add_argument("--inventory", default=INVENTORY)
    parser.add_argument("--report", help="write a markdown report")
    parser.add_argument("--json", dest="json_path", help="write a JSON result")
    args = parser.parse_args()

    candidate = load(args.doc)
    pinned = load(args.pinned)
    inventory = load(args.inventory)
    breaking, warnings = compare(candidate, pinned, inventory)
    report = render(candidate, args.doc, breaking, warnings)

    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            handle.write(report)
    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump(
                {
                    "candidate": args.doc,
                    "version": candidate.get("info", {}).get("version"),
                    "breaking": breaking,
                    "warnings": warnings,
                },
                handle,
                indent=2,
            )
            handle.write("\n")

    print(report)
    return 1 if breaking else 0


if __name__ == "__main__":
    raise SystemExit(main())
