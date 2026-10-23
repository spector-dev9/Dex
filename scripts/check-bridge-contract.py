#!/usr/bin/env python3
"""Compare the frozen TypeScript bridge contract with its Kotlin mirror."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TS_PROTOCOL = ROOT / "ui" / "src" / "bridge" / "protocol.ts"
KT_ERRORS = (
    ROOT
    / "core"
    / "common-api"
    / "src"
    / "main"
    / "kotlin"
    / "io"
    / "github"
    / "spectordev9"
    / "opencode"
    / "core"
    / "common"
    / "ApiResult.kt"
)
KT_TIMEOUTS = (
    ROOT
    / "core"
    / "common-api"
    / "src"
    / "main"
    / "kotlin"
    / "io"
    / "github"
    / "spectordev9"
    / "opencode"
    / "core"
    / "common"
    / "Timeouts.kt"
)
KT_BRIDGE = (
    ROOT
    / "core"
    / "bridge-api"
    / "src"
    / "main"
    / "kotlin"
    / "io"
    / "github"
    / "spectordev9"
    / "opencode"
    / "core"
    / "bridge"
    / "BridgeProtocol.kt"
)
KT_COMMANDS = KT_BRIDGE.with_name("BridgeCommandNames.kt")
TIMEOUT_NAMES = {
    "health": "HEALTH_MILLIS",
    "listGet": "LIST_GET_MILLIS",
    "fileRead": "FILE_READ_MILLIS",
    "promptAsync": "PROMPT_ASYNC_MILLIS",
    "abort": "ABORT_MILLIS",
    "sseIdleWatchdog": "SSE_IDLE_MILLIS",
    "gitNetwork": "GIT_NETWORK_MILLIS",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def number(value: str) -> int:
    return int(value.replace("_", ""))


def block(text: str, start: str, end: str, source: Path) -> str:
    match = re.search(re.escape(start) + r"(.*?)" + re.escape(end), text, re.DOTALL)
    if match is None:
        raise ValueError(f"{source}: could not find {start!r} block")
    return match.group(1)


def main() -> int:
    errors: list[str] = []
    ts = read(TS_PROTOCOL)
    kt_errors = read(KT_ERRORS)
    kt_timeouts = read(KT_TIMEOUTS)
    kt_bridge = read(KT_BRIDGE)
    kt_commands = read(KT_COMMANDS)

    ts_version_match = re.search(
        r"export const PROTOCOL_VERSION = (\d+) as const;", ts
    )
    kt_version_match = re.search(
        r"const val VERSION: Int = (\d+)", kt_bridge
    )
    if ts_version_match is None or kt_version_match is None:
        errors.append("protocol version is missing from one side")
    elif ts_version_match.group(1) != kt_version_match.group(1):
        errors.append(
            "protocol version differs: "
            f"TypeScript {ts_version_match.group(1)}, Kotlin {kt_version_match.group(1)}"
        )

    ts_error_block = block(
        ts,
        "export type ErrorCode =",
        ";",
        TS_PROTOCOL,
    )
    ts_error_codes = re.findall(r'"([A-Z_]+)"', ts_error_block)
    kt_error_block = block(
        kt_errors,
        "enum class ErrorCode(val retryable: Boolean) {",
        "\n}",
        KT_ERRORS,
    )
    kt_error_codes = re.findall(r"\b(E_[A-Z_]+)\((true|false)\)", kt_error_block)
    if ts_error_codes != [name for name, _ in kt_error_codes]:
        errors.append(
            "error code order/names differ:\n"
            f"  TypeScript: {ts_error_codes}\n"
            f"  Kotlin: {[name for name, _ in kt_error_codes]}"
        )

    ts_retryable_block = block(
        ts,
        "export const RETRYABLE: Readonly<Record<ErrorCode, boolean>> = {",
        "};",
        TS_PROTOCOL,
    )
    ts_retryable = dict(
        re.findall(r"\b(E_[A-Z_]+):\s*(true|false)", ts_retryable_block)
    )
    kt_retryable = dict(kt_error_codes)
    if ts_retryable != kt_retryable:
        errors.append(
            "retryable flags differ:\n"
            f"  TypeScript: {ts_retryable}\n"
            f"  Kotlin: {kt_retryable}"
        )

    ts_timeout_block = block(ts, "export const TIMEOUTS = {", "} as const;", TS_PROTOCOL)
    ts_timeouts = {
        key: number(value)
        for key, value in re.findall(r"(\w+):\s*([\d_]+)", ts_timeout_block)
    }
    kt_timeouts = {
        name: number(value)
        for name, value in re.findall(
            r"const val ([A-Z_]+): Long = ([\d_]+)", kt_timeouts
        )
    }
    for ts_name, kt_name in TIMEOUT_NAMES.items():
        if ts_name not in ts_timeouts:
            errors.append(f"TypeScript timeout {ts_name} is missing")
        if kt_name not in kt_timeouts:
            errors.append(f"Kotlin timeout {kt_name} is missing")
        if (
            ts_name in ts_timeouts
            and kt_name in kt_timeouts
            and ts_timeouts[ts_name] != kt_timeouts[kt_name]
        ):
            errors.append(
                f"timeout {ts_name} differs: "
                f"TypeScript {ts_timeouts[ts_name]}, Kotlin {kt_timeouts[kt_name]}"
            )

    ts_topic_block = block(ts, "export type EventTopic =", ";", TS_PROTOCOL)
    ts_topics = re.findall(r'"([a-z.]+)"', ts_topic_block)
    kt_topic_block = block(
        kt_bridge,
        "enum class EventTopic(val wireName: String) {",
        "\n}",
        KT_BRIDGE,
    )
    kt_topics = re.findall(r'\b[A-Z_]+(?:\("[a-z.]+"\))', kt_topic_block)
    kt_topic_names = re.findall(r'"([a-z.]+)"', kt_topic_block)
    if ts_topics != kt_topic_names:
        errors.append(
            "event topics differ:\n"
            f"  TypeScript: {ts_topics}\n"
            f"  Kotlin: {kt_topic_names} (from {len(kt_topics)} entries)"
        )

    for ts_type, kt_name in (("cmd", "TYPE_COMMAND"), ("res", "TYPE_RESULT"), ("evt", "TYPE_EVENT")):
        if f'type: "{ts_type}"' not in ts:
            errors.append(f"TypeScript envelope type {ts_type} is missing")
        if f'const val {kt_name}: String = "{ts_type}"' not in kt_bridge:
            errors.append(f"Kotlin envelope type {kt_name} is missing")

    command_names = re.findall(r'const val [A-Z_]+ = "([a-z.A-Z]+)"', kt_commands)
    if len(command_names) != len(set(command_names)):
        errors.append("Kotlin bridge command names contain duplicates")
    if not command_names:
        errors.append("Kotlin bridge command names are missing")

    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        print(f"bridge contract: {len(errors)} error(s)", file=sys.stderr)
        return 1

    print(
        "bridge contract: "
        f"v{ts_version_match.group(1)}, "
        f"{len(ts_error_codes)} errors, "
        f"{len(ts_timeouts)} timeouts, "
        f"{len(ts_topics)} event topics, "
        f"{len(command_names)} commands, 0 errors"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
