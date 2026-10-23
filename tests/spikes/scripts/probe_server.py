#!/usr/bin/env python3
"""Phase 2 probe: start the pinned OpenCode server and record behavior.

Answers S5 (endpoint inventory, PTY routes, auth) and starts S4 (SSE
heartbeat/ordering). Writes raw evidence under tests/spikes/results/raw.

Usage:
  python3 tests/spikes/scripts/probe_server.py --label no-auth
  python3 tests/spikes/scripts/probe_server.py --label with-auth --password test-secret
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
RUNTIME = os.path.join(ROOT, "runtime", "artifacts", "opencode-1.19.3")
OUT_DIR = os.path.join(ROOT, "tests", "spikes", "results", "raw")
WORKSPACE = os.path.join(ROOT, "runtime", "tmp", "workspace")
HOME = os.path.join(ROOT, "runtime", "tmp", "home")
TMP = os.path.join(ROOT, "runtime", "tmp", "tmp")


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def server_env(password: str | None) -> dict[str, str]:
    env = {
        "HOME": HOME,
        "XDG_CONFIG_HOME": os.path.join(HOME, ".config"),
        "XDG_DATA_HOME": os.path.join(HOME, ".local", "share"),
        "XDG_CACHE_HOME": os.path.join(HOME, ".cache"),
        "TMPDIR": TMP,
        "TMP": TMP,
        "TEMP": TMP,
        "SHELL": "/system/bin/sh",
        "TERM": "xterm-256color",
        "PATH": "/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin",
        "LD_LIBRARY_PATH": RUNTIME,
        "OPENCODE_DISABLE_AUTOUPDATE": "1",
        "OPENCODE_DISABLE_MODELS_FETCH": "1",
        "OPENCODE_DISABLE_LSP_DOWNLOAD": "1",
        "OPENCODE_DISABLE_DEFAULT_PLUGINS": "1",
        "OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER": "1",
        "OPENCODE_DISABLE_FFF": "1",
    }
    if password:
        env["OPENCODE_SERVER_PASSWORD"] = password
    return env


def start_server(port: int, password: str | None, log_path: str) -> subprocess.Popen:
    for path in (HOME, TMP, WORKSPACE):
        os.makedirs(path, exist_ok=True)
    command = [
        os.path.join(RUNTIME, "ld-musl-aarch64.so.1"),
        "--library-path",
        RUNTIME,
        os.path.join(RUNTIME, "opencode"),
        "serve",
        "--hostname",
        "127.0.0.1",
        "--port",
        str(port),
        "--print-logs",
        "--log-level",
        "INFO",
    ]
    log = open(log_path, "wb")
    return subprocess.Popen(
        command,
        cwd=WORKSPACE,
        env=server_env(password),
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


def wait_health(port: int, password: str | None, timeout: float) -> tuple[bool, float, int]:
    url = f"http://127.0.0.1:{port}/global/health"
    headers = {}
    if password:
        token = base64.b64encode(f"opencode:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    started = time.monotonic()
    while time.monotonic() - started < timeout:
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=2) as response:
                response.read()
                return True, time.monotonic() - started, response.status
        except urllib.error.HTTPError as error:
            return False, time.monotonic() - started, error.code
        except Exception:
            time.sleep(0.25)
    return False, time.monotonic() - started, 0


def request_status(port: int, path: str, headers: dict[str, str], timeout: float = 5) -> int:
    url = f"http://127.0.0.1:{port}{path}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
            response.read(2048)
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception as error:
        return f"error:{type(error).__name__}"


def basic(user: str, password: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


def capture_doc(port: int, headers: dict[str, str], out_path: str) -> int:
    url = f"http://127.0.0.1:{port}/doc"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=15) as response:
        body = response.read()
        with open(out_path, "wb") as handle:
            handle.write(body)
        return response.status


def capture_sse(
    port: int,
    headers: dict[str, str],
    seconds: float,
    out_path: str,
    path: str = "/event",
) -> dict:
    """Raw-socket SSE capture: records exact bytes, chunk timing and closure."""
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
    lines = [
        f"GET {path} HTTP/1.1",
        f"Host: 127.0.0.1:{port}",
        "Accept: text/event-stream",
        "Connection: close",
    ]
    for name, value in headers.items():
        lines.append(f"{name}: {value}")
    sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())

    result = {"chunks": [], "closedByServer": False, "timedOut": False}
    raw = bytearray()
    started = time.monotonic()
    sock.settimeout(0.5)
    while time.monotonic() - started < seconds:
        try:
            data = sock.recv(4096)
        except TimeoutError:
            continue
        except Exception as error:
            result["error"] = type(error).__name__
            break
        if not data:
            result["closedByServer"] = True
            result["closedAt"] = round(time.monotonic() - started, 3)
            break
        result["chunks"].append(
            {"at": round(time.monotonic() - started, 3), "bytes": len(data), "text": data.decode("utf-8", "replace")}
        )
        raw.extend(data)
    else:
        result["timedOut"] = True
    sock.close()
    with open(out_path, "wb") as handle:
        handle.write(bytes(raw))

    text = bytes(raw).decode("utf-8", "replace")
    header_end = text.find("\r\n\r\n")
    result["status"] = None
    result["headers"] = {}
    if header_end >= 0:
        head, body = text[:header_end], text[header_end + 4:]
        status_line = head.splitlines()[0] if head.splitlines() else ""
        parts = status_line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            result["status"] = int(parts[1])
        for line in head.splitlines()[1:]:
            if ":" in line:
                name, value = line.split(":", 1)
                result["headers"][name.strip()] = value.strip()
        result["bodyBytes"] = len(body)
        result["eventTypes"] = re.findall(r'"type":"([^"]+)"', body)
        result["dataLines"] = re.findall(r"^data: (.+)$", body, re.MULTILINE)
    result["elapsed"] = round(time.monotonic() - started, 3)
    result["totalBytes"] = len(raw)
    return result


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--password", default=None)
    parser.add_argument("--sse-seconds", type=float, default=12)
    parser.add_argument("--startup-timeout", type=float, default=90)
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    port = free_port()
    log_path = os.path.join(OUT_DIR, f"{args.label}-server.log")
    process = start_server(port, args.password, log_path)
    summary: dict = {
        "label": args.label,
        "port": port,
        "passwordConfigured": bool(args.password),
        "runtimeVersion": "1.19.3",
        "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }

    try:
        healthy, seconds, status = wait_health(port, args.password, args.startup_timeout)
        summary["health"] = {"healthy": healthy, "seconds": round(seconds, 3), "status": status}
        if not healthy:
            summary["serverLogTail"] = open(log_path, encoding="utf-8", errors="replace").read()[-4000:]
            return_code = process.poll()
            summary["exitCode"] = return_code
            with open(os.path.join(OUT_DIR, f"{args.label}-summary.json"), "w", encoding="utf-8") as handle:
                json.dump(summary, handle, indent=2)
            print(json.dumps(summary, indent=2))
            return 1

        workspace_query = f"/session?directory={urllib.parse.quote(WORKSPACE)}"
        pty_query = f"/pty?directory={urllib.parse.quote(WORKSPACE)}"
        headers = {}
        if args.password:
            headers = basic("opencode", args.password)

        matrix = {}
        for name, path in {
            "health": "/global/health",
            "doc": "/doc",
            "project": "/project",
            "session": workspace_query,
            "pty": pty_query,
            "event": "/event",
        }.items():
            matrix[name] = {
                "noAuth": request_status(port, path, {}, timeout=3),
                "basicOpencode": request_status(port, path, basic("opencode", args.password or "test-secret"), timeout=3),
                "basicEmptyUser": request_status(port, path, basic("", args.password or "test-secret"), timeout=3),
                "bearer": request_status(port, path, {"Authorization": f"Bearer {args.password or 'test-secret'}"}, timeout=3),
                "wrongPassword": request_status(port, path, basic("opencode", "wrong-password"), timeout=3),
            }
        summary["authMatrix"] = matrix

        doc_path = os.path.join(ROOT, "contract", "openapi", f"opencode-{args.label}.json")
        os.makedirs(os.path.dirname(doc_path), exist_ok=True)
        try:
            summary["doc"] = {"status": capture_doc(port, headers, doc_path), "path": doc_path}
        except Exception as error:
            summary["doc"] = {"error": repr(error)}

        sse_path = os.path.join(OUT_DIR, f"{args.label}-event.sse")
        first = capture_sse(port, headers, args.sse_seconds, sse_path)
        time.sleep(1)
        sse_path_2 = os.path.join(OUT_DIR, f"{args.label}-event-reconnect.sse")
        second = capture_sse(port, headers, min(6.0, args.sse_seconds), sse_path_2)
        scoped_path = os.path.join(OUT_DIR, f"{args.label}-event-scoped.sse")
        scoped = capture_sse(
            port,
            headers,
            min(12.0, args.sse_seconds),
            scoped_path,
            path=f"/event?directory={urllib.parse.quote(WORKSPACE)}",
        )
        summary["sse"] = {"first": first, "reconnect": second, "directoryScoped": scoped}
        summary["sse"]["first"]["path"] = sse_path
        summary["sse"]["reconnect"]["path"] = sse_path_2
        summary["sse"]["directoryScoped"]["path"] = scoped_path
        first_ids = re.findall(r'"id":"(evt_[^"]+)"', first.get("dataLines", [""])[0]) if first.get("dataLines") else []
        second_ids = re.findall(r'"id":"(evt_[^"]+)"', second.get("dataLines", [""])[0]) if second.get("dataLines") else []
        summary["sse"]["reconnectDeliveredNewConnectedEvent"] = bool(
            first_ids and second_ids and first_ids[0] != second_ids[0]
        )

        with open(os.path.join(OUT_DIR, f"{args.label}-summary.json"), "w", encoding="utf-8") as handle:
            json.dump(summary, handle, indent=2)
        print(json.dumps(summary, indent=2))
        return 0
    finally:
        stop(process)


if __name__ == "__main__":
    raise SystemExit(main())
