package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlpineNodePackageIntegrationTest {
    @Test
    fun `installs fetched Alpine node package archives into an arm64 rootfs`() {
        val root = createTempDirectory("alpine-node-real-apks")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val artifacts = Path.of("../../runtime/artifacts").toAbsolutePath().normalize()
        val installer = AlpineRuntimePackageInstaller()
        val packages = listOf(
            "nodejs" to ("20.15.1-r0" to "2e75ebaa9d9eb5d5b17dd21d4907dbf9259c30b33003e7be9501b778fc8d859f"),
            "ca-certificates" to ("20261020-r0" to "50ecdec3d7f291491bc11b8db4cdf051dbad8ea7258f5cff75964c97d825d93e"),
            "ada-libs" to ("2.7.8-r0" to "64a7e8b02c79562dc8f5b07abfcc9c657281c4bed3e364e213156e92ab9089c3"),
            "libbase64" to ("0.5.2-r0" to "73cdea2524e0d82e3910051d388bce974498818e906e816da93a4aa130f5fed7"),
            "brotli-libs" to ("1.1.0-r2" to "2146736e8d34bb9389c7902b942066d17bf36439a5c44aeb9b21d625d992f55d"),
            "c-ares" to ("1.33.1-r0" to "f07a70d10515800596a93a2a4de8847fb6ca8f336d0d1d6bc721c01373a43df9"),
            "libcrypto3" to ("3.3.7-r0" to "2831d5455f72447b266be24800b51dbadcbadf6628611cbb0e7c81f467552967"),
            "icu-libs" to ("74.2-r1" to "193583994519ca266a7b8e768483dc0d361868b5f22a2a1345bdabd3fbd04ee0"),
            "nghttp2-libs" to ("1.62.1-r0" to "1391bc9bae1a8e29e0179e730022e428ed1a6efe875aa862b675635c8c3f6bb7"),
            "libssl3" to ("3.3.7-r0" to "7074e5b8fa4560e4bfd1566da365f2cf33c76be91eeeaf27d95a99fe00552164"),
            "zlib" to ("1.3.2-r0" to "08057738baed0c0e2e98a0c064abaa658974915e874664fc78694999bec37609"),
        )

        packages.forEach { (name, pin) ->
            val (version, digest) = pin
            val result = installer.install(
                archive = artifacts.resolve("$name-$version.apk"),
                expectedArchiveSha256 = digest,
                packageName = name,
                rootfsDirectory = rootfs,
            )
            if (result is ApiResult.Failure) {
                throw AssertionError("$name $version failed: ${result.error.message}")
            }
            assertIs<ApiResult.Success<Path>>(result)
        }

        assertTrue(Files.isRegularFile(rootfs.resolve("usr/bin/node")))
        assertTrue(Files.isExecutable(rootfs.resolve("usr/bin/node")))
        assertTrue(Files.isRegularFile(rootfs.resolve("lib/libcrypto.so.3")))
    }
}
