package io.github.spectordev9.opencode.security

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.SecretRef
import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EncryptedSecretFilesTest {
    @Test
    fun `stores only cipher output and returns an owned secret copy`() {
        val appFiles = createTempDirectory("secret-files")
        val secret = "private-token".toCharArray()
        val store = EncryptedSecretFiles(appFiles, TestCipher())
        val reference = SecretRef("server-launch")

        assertIs<ApiResult.Success<Unit>>(store.put(reference, secret))

        val secretFile = Files.list(appFiles.resolve("keystore-secrets")).use { it.findFirst().orElseThrow() }
        assertFalse(Files.readAllBytes(secretFile).toString(Charsets.UTF_8).contains("private-token"))
        val retrieved = assertIs<ApiResult.Success<CharArray>>(store.get(reference)).value
        assertContentEquals(secret, retrieved)
        retrieved.fill('\u0000')
        assertContentEquals("private-token".toCharArray(), secret)
    }

    @Test
    fun `deletes one opaque secret reference without affecting another`() {
        val appFiles = createTempDirectory("secret-files-delete")
        val store = EncryptedSecretFiles(appFiles, TestCipher())
        val first = SecretRef("first")
        val second = SecretRef("second")
        store.put(first, "one".toCharArray())
        store.put(second, "two".toCharArray())

        assertIs<ApiResult.Success<Unit>>(store.delete(first))

        assertIs<ApiResult.Failure>(store.get(first))
        assertContentEquals("two".toCharArray(), assertIs<ApiResult.Success<CharArray>>(store.get(second)).value)
    }

    @Test
    fun `rejects a secret directory symlink that escapes app storage`() {
        val appFiles = createTempDirectory("secret-files-link")
        val outside = createTempDirectory("secret-files-outside")
        Files.createSymbolicLink(appFiles.resolve("keystore-secrets"), outside)
        val store = EncryptedSecretFiles(appFiles, TestCipher())

        assertIs<ApiResult.Failure>(store.put(SecretRef("reference"), "secret".toCharArray()))
        assertTrue(Files.list(outside).use { !it.findAny().isPresent })
    }

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5a).toByte() }
            .toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }
}
