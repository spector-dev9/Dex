#!/usr/bin/env python3
"""Build contract/endpoints.json from the pinned /doc snapshot.

Phase 2 spike S5. The inventory distinguishes:
  - required: endpoints the app plans to use (spec Section 4.1)
  - pty: PTY routes present in the pinned spec
  - apiV2: the /api/* surface that the spec does not mention
"""

from __future__ import annotations

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "contract", "openapi", "opencode-1.19.3.json")
OUT = os.path.join(ROOT, "contract", "endpoints.json")

# Section 4.1 uses :id / :permissionID shorthand; the pinned spec names
# them {sessionID} / {permissionID}.
REQUIRED = [
    ("GET", "/global/health"),
    ("GET", "/global/event"),
    ("GET", "/event"),
    ("GET", "/session"),
    ("POST", "/session"),
    ("GET", "/session/{sessionID}"),
    ("PATCH", "/session/{sessionID}"),
    ("DELETE", "/session/{sessionID}"),
    ("GET", "/session/status"),
    ("POST", "/session/{sessionID}/fork"),
    ("POST", "/session/{sessionID}/abort"),
    ("POST", "/session/{sessionID}/revert"),
    ("POST", "/session/{sessionID}/unrevert"),
    ("POST", "/session/{sessionID}/summarize"),
    ("GET", "/session/{sessionID}/diff"),
    ("GET", "/session/{sessionID}/message"),
    ("POST", "/session/{sessionID}/prompt_async"),
    ("POST", "/session/{sessionID}/command"),
    ("POST", "/session/{sessionID}/shell"),
    ("POST", "/session/{sessionID}/permissions/{permissionID}"),
    ("GET", "/config/providers"),
    ("GET", "/provider"),
    ("GET", "/agent"),
    ("GET", "/command"),
    ("PATCH", "/config"),
    ("PUT", "/auth/{providerID}"),
    ("GET", "/project"),
    ("GET", "/project/current"),
    ("GET", "/path"),
    ("GET", "/vcs"),
    ("GET", "/file/status"),
    ("GET", "/file"),
    ("GET", "/file/content"),
    ("GET", "/find"),
    ("GET", "/find/file"),
    ("GET", "/find/symbol"),
]

SPEC_ONLY = [
    ("GET", "/doc", "serves its own spec but is not listed as a path"),
]


def main() -> int:
    with open(DOC, encoding="utf-8") as handle:
        doc = json.load(handle)

    paths = doc.get("paths", {})
    required = []
    for method, path in REQUIRED:
        methods = sorted(paths.get(path, {}).keys())
        required.append(
            {
                "method": method,
                "path": path,
                "present": method.lower() in methods,
                "methods": methods,
            }
        )

    pty = []
    for path, operations in sorted(paths.items()):
        if path == "/pty" or path.startswith("/pty/") or path.startswith("/api/pty"):
            pty.append({"path": path, "methods": sorted(operations.keys())})

    api_v2 = sorted(p for p in paths if p.startswith("/api/"))
    missing = [entry for entry in required if not entry["present"]]

    inventory = {
        "pinnedVersion": "1.19.3",
        "source": "contract/openapi/opencode-1.19.3.json (live GET /doc)",
        "openapi": doc.get("openapi"),
        "title": doc.get("info", {}).get("title"),
        "pathCount": len(paths),
        "required": required,
        "missingRequired": [f"{e['method']} {e['path']}" for e in missing],
        "specOnly": [
            {"method": method, "path": path, "note": note}
            for method, path, note in SPEC_ONLY
        ],
        "pty": {
            "documentedInPinnedSpec": bool(pty),
            "routes": pty,
            "note": (
                "The spec's F3 said PTY routes were undocumented. The pinned "
                "1.19.3 /doc documents /pty and /api/pty. D10 still chooses a "
                "native PTY so the terminal works when the server is down; "
                "revisit the rationale at G0."
            ),
        },
        "apiV2": {
            "count": len(api_v2),
            "note": "The pinned spec exposes an /api/* surface not listed in Section 4.1.",
            "paths": api_v2,
        },
    }

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(inventory, handle, indent=2)
        handle.write("\n")

    print(f"wrote {OUT}")
    print(f"openapi={inventory['openapi']} paths={inventory['pathCount']} "
          f"required={len(required)} missing={len(missing)} pty={len(pty)} apiV2={len(api_v2)}")
    if missing:
        for entry in missing:
            print(f"MISSING {entry['method']} {entry['path']}")
    return 0 if not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
