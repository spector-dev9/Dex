package io.github.spectordev9.opencode.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Guards the production runtime install path (RT-3): everything
 * `PinnedRuntimeInstaller` reads from the pinned manifest must be present,
 * well-formed and internally consistent, so a first-run install never fails
 * on a schema drift.
 */
class PinnedRuntimeManifestTest {

    private val manifest: JsonObject by lazy {
        val file = locateManifest()
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun string(objectValue: JsonObject, key: String): String =
        ((objectValue[key] as? JsonPrimitive)?.contentOrNull)
            ?: fail("missing string field: $key")

    private fun objectValue(objectValue: JsonObject, key: String): JsonObject =
        objectValue[key]?.jsonObject ?: fail("missing object field: $key")

    private fun array(objectValue: JsonObject, key: String): JsonArray =
        objectValue[key]?.jsonArray ?: fail("missing array field: $key")

    /** CI stages runtime/manifest.json at the repo root; walk up from the test working dir. */
    private fun locateManifest(): File {
        val workingDirectory = System.getProperty("user.dir")
            ?: fail("user.dir system property is unavailable")
        var dir = File(workingDirectory).absoluteFile
        repeat(6) {
            val candidate = File(dir, "runtime/manifest.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        fail("runtime/manifest.json not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `manifest carries a well formed pinned version`() {
        val version = string(manifest, "version")
        assertTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "version=$version")
    }

    @Test
    fun `artifact and alpine entries are complete`() {
        val artifact = objectValue(manifest, "artifact")
        assertTrue(string(artifact, "url").startsWith("https://"))
        assertTrue(string(artifact, "sha256").matches(Regex("[a-fA-F0-9]{64}")))

        val alpine = objectValue(manifest, "alpine")
        assertTrue(string(alpine, "url").startsWith("https://"))
        assertTrue(string(alpine, "sha256").matches(Regex("[a-fA-F0-9]{64}")))
    }

    @Test
    fun `binary pin matches the required executable`() {
        assertEquals("opencode", string(manifest, "binary"))
        assertTrue(
            string(manifest, "binarySha256").matches(Regex("[a-fA-F0-9]{64}")),
        )
        val required = array(manifest, "required")
        assertEquals(1, required.size)
        val requiredName = (required[0] as? JsonPrimitive)?.contentOrNull
            ?: fail("required entry is not a string")
        assertEquals("opencode", requiredName)
    }

    @Test
    fun `every alpine runtime package maps to a supported library`() {
        // Must stay in sync with PinnedRuntimeInstaller.runtimePackageLibraries.
        val supported = mapOf(
            "libgcc" to "libgcc_s.so.1",
            "libstdc++" to "libstdc++.so.6",
        )
        val packages = array(manifest, "alpineRuntimePackages")
        for (element in packages) {
            val spec = element.jsonObject
            val name = string(spec, "name")
            assertTrue(supported.containsKey(name), "unsupported pinned package: $name")
            assertTrue(string(spec, "url").startsWith("https://"))
            assertTrue(string(spec, "sha256").matches(Regex("[a-fA-F0-9]{64}")))
        }
    }

    @Test
    fun `node runtime packages install a real executable`() {
        val node = objectValue(manifest, "nodeRuntime")
        val executable = string(node, "executable")
        assertTrue(executable.startsWith("usr/bin/"), "executable=$executable")
        val packages = array(node, "packages")
        assertTrue(packages.isNotEmpty(), "nodeRuntime needs at least the nodejs package")
        for (element in packages) {
            val spec = element.jsonObject
            assertTrue(string(spec, "url").startsWith("https://"))
            assertTrue(string(spec, "sha256").matches(Regex("[a-fA-F0-9]{64}")))
        }
    }

    @Test
    fun `runtime libraries are unique and well named`() {
        val libraries = array(manifest, "runtimeLibraries")
        val names = mutableSetOf<String>()
        for (element in libraries) {
            val name = (element as? JsonPrimitive)?.contentOrNull ?: fail("invalid library name")
            assertTrue(
                name.matches(Regex("[A-Za-z0-9_.+-]+")),
                "invalid runtime library name: $name",
            )
            assertTrue(names.add(name), "duplicate runtime library: $name")
        }
        assertTrue(names.isNotEmpty(), "runtimeLibraries must not be empty")
    }
}
