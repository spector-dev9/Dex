#!/usr/bin/env python3
"""Unit tests for the release manifest security checker."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

checker_path = Path(__file__).resolve().parents[2] / "scripts" / "check-release-manifest.py"
checker_spec = importlib.util.spec_from_file_location("check_release_manifest", checker_path)
if checker_spec is None or checker_spec.loader is None:
    raise RuntimeError(f"cannot load manifest checker at {checker_path}")
checker = importlib.util.module_from_spec(checker_spec)
sys.modules[checker_spec.name] = checker
checker_spec.loader.exec_module(checker)
check_manifest = checker.check_manifest


VALID_MANIFEST = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="example.app">
  <uses-permission android:name="android.permission.INTERNET" />
  <application android:allowBackup="false">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
    <activity android:name=".InternalActivity" android:exported="false" />
  </application>
</manifest>
"""


class ReleaseManifestTest(unittest.TestCase):
    def check_xml(self, xml: str) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(xml, encoding="utf-8")
            return check_manifest(path)

    def test_accepts_only_an_explicitly_exported_launcher(self) -> None:
        self.assertEqual([], self.check_xml(VALID_MANIFEST))

    def test_rejects_exported_non_launcher_components(self) -> None:
        xml = VALID_MANIFEST.replace(
            '<activity android:name=".InternalActivity" android:exported="false" />',
            '<activity android:name=".InternalActivity" android:exported="true" />',
        )
        errors = self.check_xml(xml)
        self.assertTrue(any("non-launcher component is exported" in error for error in errors))

    def test_rejects_debuggable_or_backed_up_application(self) -> None:
        xml = VALID_MANIFEST.replace('android:allowBackup="false"', 'android:allowBackup="true"')
        xml = xml.replace(
            '<application android:allowBackup="true">',
            '<application android:allowBackup="true" android:debuggable="true">',
        )
        errors = self.check_xml(xml)
        self.assertTrue(any("must not be debuggable" in error for error in errors))
        self.assertTrue(any("allowBackup=false" in error for error in errors))

    def test_rejects_unapproved_permissions_and_global_cleartext(self) -> None:
        xml = VALID_MANIFEST.replace(
            'android:allowBackup="false"',
            'android:allowBackup="false" android:usesCleartextTraffic="true"',
        )
        xml = xml.replace(
            '<uses-permission android:name="android.permission.INTERNET" />',
            '<uses-permission android:name="android.permission.INTERNET" />'
            '<uses-permission android:name="android.permission.READ_CONTACTS" />',
        )
        errors = self.check_xml(xml)
        self.assertTrue(any("application-wide cleartext" in error for error in errors))
        self.assertTrue(any("READ_CONTACTS" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
