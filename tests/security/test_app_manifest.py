#!/usr/bin/env python3
"""Unit tests for the app runtime-manifest consistency checker."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[2] / "scripts" / "check-app-manifest.py"
SPEC = importlib.util.spec_from_file_location("check_app_manifest", CHECKER)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load manifest checker at {CHECKER}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
check = MODULE.check

RUNTIME = {
    "version": "1.19.3",
    "profile": "A_DIRECT",
    "binary": "opencode",
    "binarySha256": "37c7de554c2ba7bf456e4fe6faf7705141b0ccb3a7b1dd6e1046d52cd8269ecd",
}


class AppManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def write(self, name: str, payload: object) -> Path:
        path = self.root / name
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_accepts_a_matching_manifest(self) -> None:
        app = self.write("app.json", RUNTIME)
        runtime = self.write("runtime.json", RUNTIME)
        self.assertEqual([], check(app, runtime))

    def test_rejects_invalid_json_and_placeholders(self) -> None:
        app = self.root / "app.json"
        app.write_text("{not json", encoding="utf-8")
        runtime = self.write("runtime.json", RUNTIME)
        self.assertTrue(check(app, runtime))

        placeholder = dict(RUNTIME, binarySha256="0" * 64)
        app = self.write("app2.json", placeholder)
        errors = check(app, runtime)
        self.assertTrue(any("placeholder" in error for error in errors))

    def test_rejects_drift_and_short_digest(self) -> None:
        runtime = self.write("runtime.json", RUNTIME)
        for field, value in (
            ("version", "9.9.9"),
            ("profile", "B_PROOT"),
            ("binary", "other"),
            ("binarySha256", "ab" * 31),
        ):
            app = self.write(f"app-{field}.json", dict(RUNTIME, **{field: value}))
            self.assertTrue(check(app, runtime), field)


if __name__ == "__main__":
    unittest.main()
