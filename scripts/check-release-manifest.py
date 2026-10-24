#!/usr/bin/env python3
"""Check security invariants in the merged Android release manifest."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
}
COMPONENT_TAGS = {"activity", "activity-alias", "service", "receiver", "provider"}


def check_manifest(path: Path) -> list[str]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as failure:
        return [f"{path}: cannot read merged manifest: {failure}"]

    errors: list[str] = []
    package = root.get("package")
    if not package:
        errors.append("manifest must declare the application package")
    application = root.find("application")
    if application is None:
        return [f"{path}: missing application element"]

    if application.get(f"{ANDROID}debuggable") != "false":
        errors.append("release application must explicitly set debuggable=false")
    if application.get(f"{ANDROID}allowBackup") != "false":
        errors.append("release application must set allowBackup=false")
    if application.get(f"{ANDROID}usesCleartextTraffic") == "true":
        errors.append("application-wide cleartext traffic must not be enabled")

    launcher_components: list[ET.Element] = []
    exported_components: list[str] = []
    for component in application:
        if component.tag.rsplit("}", 1)[-1] not in COMPONENT_TAGS:
            continue
        name = component.get(f"{ANDROID}name", "<unnamed>")
        exported = component.get(f"{ANDROID}exported")
        if exported not in {"true", "false"}:
            errors.append(f"component must explicitly set android:exported: {name}")
        has_launcher_filter = any(
            action.get(f"{ANDROID}name") == "android.intent.action.MAIN"
            and any(
                category.get(f"{ANDROID}name") == "android.intent.category.LAUNCHER"
                for category in intent_filter.findall("category")
            )
            for intent_filter in component.findall("intent-filter")
            for action in intent_filter.findall("action")
        )
        is_launcher_activity = (
            component.tag.rsplit("}", 1)[-1] in {"activity", "activity-alias"}
            and has_launcher_filter
        )
        if is_launcher_activity:
            launcher_components.append(component)
        if exported == "true":
            exported_components.append(name)
            if not is_launcher_activity:
                errors.append(f"non-launcher component is exported: {name}")

    if len(launcher_components) != 1:
        errors.append(f"expected exactly one launcher component, found {len(launcher_components)}")
    elif launcher_components[0].get(f"{ANDROID}exported") != "true":
        errors.append("launcher component must be explicitly exported")
    if len(exported_components) != 1:
        errors.append(f"expected only the launcher to be exported, found {exported_components}")

    defined_permissions = {
        permission.get(f"{ANDROID}name"): permission
        for permission in root.findall("permission")
    }
    for permission_tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        for element in root.findall(permission_tag):
            name = element.get(f"{ANDROID}name", "")
            if name in ALLOWED_PERMISSIONS or name.startswith("android.permission.FOREGROUND_SERVICE_"):
                continue
            generated_name = f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            declared = defined_permissions.get(name)
            if name == generated_name and declared is not None:
                if declared.get(f"{ANDROID}protectionLevel") == "signature":
                    continue
            errors.append(f"unexpected uses-permission: {name}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="merged release AndroidManifest.xml")
    args = parser.parse_args()

    errors = check_manifest(args.manifest)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"release manifest security checks passed: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
