#!/usr/bin/env python3
"""Enforce the G1 core-module dependency and package boundaries."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SETTINGS = ROOT / "settings.gradle.kts"
ROOT_BUILD = ROOT / "build.gradle.kts"
PACKAGE_PREFIX = "io.github.spectordev9.opencode.core"
MODULE_PACKAGES = {
    "bridge-api": "bridge",
    "common-api": "common",
    "diagnostics-api": "diagnostics",
    "gateway-api": "gateway",
    "git-api": "git",
    "model": "model",
    "opencode-client": "opencode.client",
    "runtime": "runtime.impl",
    "runtime-api": "runtime",
    "security": "security.impl",
    "security-api": "security",
    "supervisor-api": "supervisor",
    "terminal-api": "terminal",
    "workspace": "workspace.impl",
    "workspace-api": "workspace",
}
EXPECTED_MODULES = tuple(MODULE_PACKAGES)
ALLOWED_PROJECT_DEPENDENCIES = {
    module: (set() if module in {"common-api", "model"} else {"common-api"})
    for module in EXPECTED_MODULES
}
ALLOWED_PROJECT_DEPENDENCIES["opencode-client"] = {
    "common-api",
    "gateway-api",
    "model",
}
ALLOWED_PROJECT_DEPENDENCIES["runtime"] = {"common-api", "runtime-api"}
ALLOWED_PROJECT_DEPENDENCIES["security"] = {"common-api", "security-api"}
ALLOWED_PROJECT_DEPENDENCIES["workspace"] = {"common-api", "workspace-api"}
ALLOWED_EXTERNAL_DEPENDENCIES = {
    "common-api": {"libs.kotlinx.coroutines.core"},
    "model": {
        "libs.kotlinx.serialization.json",
        "libs.junit.jupiter",
        "libs.junit.platform.launcher",
    },
    "runtime": {
        "libs.kotlinx.serialization.json",
        "libs.junit.jupiter",
        "libs.junit.platform.launcher",
    },
    "security": {
        "libs.junit.jupiter",
        "libs.junit.platform.launcher",
    },
    "workspace": {
        "libs.kotlinx.coroutines.core",
        "libs.junit.jupiter",
        "libs.junit.platform.launcher",
    },
    "opencode-client": {
        "libs.kotlinx.coroutines.core",
        "libs.kotlinx.serialization.json",
        "libs.okhttp",
        "libs.okhttp.sse",
        "libs.junit.jupiter",
        "libs.junit.platform.launcher",
        "libs.mockwebserver",
    },
}
ALLOWED_PLUGIN_REFERENCES = {
    "libs.plugins.kotlin.jvm",
    "libs.plugins.kotlin.serialization",
}
SELF_MODULE_PATTERN = re.compile(
    r"^io\.github\.spectordev9\.opencode\.core\.([a-z]+(?:\.[a-z]+)*)"
)
ANDROID_IMPORT_PATTERN = re.compile(r"^(android|androidx|com\.android)\.")
DIRECT_DEPENDENCY = re.compile(
    r"\b(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*\"([^\"]+)\""
)
PROJECT_DEPENDENCY = re.compile(r'project\("(:[^"]+)"\)')
CATALOG_REFERENCE = re.compile(r"\blibs\.[A-Za-z0-9_.]+")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def check_module_inventory(errors: list[str]) -> None:
    settings = read(SETTINGS)
    includes = set(re.findall(r'"(:[^"]+)"', settings))
    expected = {f":core:{module}" for module in EXPECTED_MODULES}
    missing = sorted(expected - includes)
    extra = sorted(
        include
        for include in includes - expected
        if include.startswith(":core:")
    )
    for include in missing:
        errors.append(f"{SETTINGS}: missing include {include}")
    for include in extra:
        errors.append(f"{SETTINGS}: unexpected core include {include}")

    for token in (
        "alias(libs.plugins.kotlin.jvm) apply false",
        "jvmToolchain(21)",
    ):
        if token not in read(ROOT_BUILD):
            errors.append(f"{ROOT_BUILD}: missing {token}")


def module_dependencies(module: str, errors: list[str]) -> list[str]:
    path = ROOT / "core" / module / "build.gradle.kts"
    if not path.is_file():
        errors.append(f"{path}: missing module build file")
        return []

    text = read(path)
    dependencies: list[str] = []
    expected_project = ALLOWED_PROJECT_DEPENDENCIES[module]
    for target in PROJECT_DEPENDENCY.findall(text):
        if not target.startswith(":core:"):
            errors.append(f"{path}: non-core project dependency {target}")
            continue
        target_module = target.removeprefix(":core:")
        dependencies.append(target_module)
        if target_module not in expected_project:
            errors.append(f"{path}: {module} may not depend on {target_module}")

    allowed_external = ALLOWED_EXTERNAL_DEPENDENCIES.get(module, set())
    for reference in CATALOG_REFERENCE.findall(text):
        if reference in ALLOWED_PLUGIN_REFERENCES:
            continue
        if reference not in allowed_external:
            errors.append(f"{path}: external dependency is not allowed: {reference}")

    for coordinate in DIRECT_DEPENDENCY.findall(text):
        errors.append(f"{path}: direct dependency is not allowed: {coordinate}")

    return dependencies


def check_no_android_plugin(errors: list[str]) -> None:
    paths = [SETTINGS, ROOT_BUILD] + [
        ROOT / "core" / module / "build.gradle.kts"
        for module in EXPECTED_MODULES
    ]
    for path in paths:
        if not path.is_file():
            continue
        text = read(path)
        for marker in ("com.android", "android {"):
            if marker in text:
                errors.append(f"{path}: Android Gradle plugin code is forbidden")


def check_sources(module: str, dependencies: list[str], errors: list[str]) -> int:
    package_module = MODULE_PACKAGES[module]
    kotlin_root = ROOT / "core" / module / "src" / "main" / "kotlin"
    expected_package_dir = (
        kotlin_root
        / Path(*PACKAGE_PREFIX.split("."))
        / Path(*package_module.split("."))
    )
    if not expected_package_dir.is_dir():
        errors.append(f"{expected_package_dir}: missing package directory")
        return 0

    files = sorted(kotlin_root.rglob("*.kt"))
    if not files:
        errors.append(f"{kotlin_root}: no Kotlin contract sources")
        return 0

    for java_file in kotlin_root.rglob("*.java"):
        errors.append(f"{java_file}: Java sources are not allowed in core contracts")

    allowed_core_imports = (
        {MODULE_PACKAGES[dependency] for dependency in dependencies}
        | {package_module}
    )
    for path in files:
        text = read(path)
        package_match = re.search(r"^package\s+([A-Za-z0-9_.]+)\s*$", text, re.MULTILINE)
        if package_match is None:
            errors.append(f"{path}: missing package declaration")
            continue
        package = package_match.group(1)
        if package != f"{PACKAGE_PREFIX}.{package_module}" and not package.startswith(
            f"{PACKAGE_PREFIX}.{package_module}."
        ):
            errors.append(f"{path}: unexpected package {package}")
        package_dir = kotlin_root / Path(*package.split("."))
        if package_dir not in path.parents:
            errors.append(f"{path}: file location does not match package {package}")

        for imported in re.findall(r"^import\s+([A-Za-z0-9_.]+)", text, re.MULTILINE):
            if ANDROID_IMPORT_PATTERN.match(imported):
                errors.append(f"{path}: Android import is forbidden: {imported}")
            module_match = SELF_MODULE_PATTERN.match(imported)
            if module_match is None:
                continue
            imported_module = module_match.group(1)
            if imported_module not in allowed_core_imports:
                errors.append(
                    f"{path}: forbidden core import {imported} "
                    f"(allowed: {sorted(allowed_core_imports)})"
                )
    return len(files)


def main() -> int:
    errors: list[str] = []
    check_module_inventory(errors)
    check_no_android_plugin(errors)

    source_count = 0
    dependency_count = 0
    for module in EXPECTED_MODULES:
        dependencies = module_dependencies(module, errors)
        dependency_count += len(dependencies)
        source_count += check_sources(module, dependencies, errors)

    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        print(f"architecture: {len(errors)} error(s)", file=sys.stderr)
        return 1

    print(
        "architecture: "
        f"{len(EXPECTED_MODULES)} modules, "
        f"{dependency_count} project dependencies, "
        f"{source_count} Kotlin files, 0 errors"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
