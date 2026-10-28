package io.github.spectordev9.opencode.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONObject

/**
 * Guards the production runtime install path (RT-3): everything
 * `PinnedRuntimeInstaller` reads from the pinned manifest must be present,
 * well-formed and internally consistent, so a first-run install never fails
 * on a schema drift.
 */
class PinnedRuntimeManifestTest {

    private val manifest: JSONObject by lazy {
        val file = locateManifest()
        JSONObject(file.readText())
    }

    /** CI stages runtime/manifest.json at the repo root; walk up from the test working dir. */
    private fun locateManifest(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "runtime/manifest.json")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        fail("runtime/manifest.json not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `manifest carries a well formed pinned version`() {
        val version = manifest.getString("version")
        assertTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "version=$version")
    }

    @Test
    fun `artifact and alpine entries are complete`() {
        val artifact = manifest.getJSONObject("artifact")
        assertTrue(artifact.getString("url").startsWith("https://"))
        assertTrue(artifact.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))

        val alpine = manifest.getJSONObject("alpine")
        assertTrue(alpine.getString("url").startsWith("https://"))
        assertTrue(alpine.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))
    }

    @Test
    fun `binary pin matches the required executable`() {
        assertEquals("opencode", manifest.getString("binary"))
        assertTrue(
            manifest.getString("binarySha256").matches(Regex("[a-fA-F0-9]{64}")),
        )
        val required = manifest.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("opencode", required.getString(0))
    }

    @Test
    fun `every alpine runtime package maps to a supported library`() {
        // Must stay in sync with PinnedRuntimeInstaller.runtimePackageLibraries.
        val supported = mapOf(
            "libgcc" to "libgcc_s.so.1",
            "libstdc++" to "libstdc++.so.6",
        )
        val packages = manifest.getJSONArray("alpineRuntimePackages")
        for (index in 0 until packages.length()) {
            val spec = packages.getJSONObject(index)
            val name = spec.getString("name")
            assertTrue(supported.containsKey(name), "unsupported pinned package: $name")
            assertTrue(spec.getString("url").startsWith("https://"))
            assertTrue(spec.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))
        }
    }

    @Test
    fun `node runtime packages install a real executable`() {
        val node = manifest.getJSONObject("nodeRuntime")
        val executable = node.getString("executable")
        assertTrue(executable.startsWith("usr/bin/"), "executable=$executable")
        val packages = node.getJSONArray("packages")
        assertTrue(packages.length() >= 1, "nodeRuntime needs at least the nodejs package")
        for (index in 0 until packages.length()) {
            val spec = packages.getJSONObject(index)
            assertTrue(spec.getString("url").startsWith("https://"))
            assertTrue(spec.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))
        }
    }

    @Test
    fun `runtime libraries are unique and well named`() {
        val libraries = manifest.getJSONArray("runtimeLibraries")
        val names = mutableSetOf<String>()
        for (index in 0 until libraries.length()) {
            val name = libraries.getString(index)
            assertTrue(
                name.matches(Regex("[A-Za-z0-9_.+-]+")),
                "invalid runtime library name: $name",
            )
            assertTrue(names.add(name), "duplicate runtime library: $name")
        }
        assertTrue(names.isNotEmpty(), "runtimeLibraries must not be empty")
    }
}
