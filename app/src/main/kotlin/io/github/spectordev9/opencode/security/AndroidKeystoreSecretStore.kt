package io.github.spectordev9.opencode.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.security.SecretStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SecretCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(ciphertext: ByteArray): ByteArray
}

class AndroidKeystoreSecretStore(
    context: Context,
) : SecretStore {
    private val files = EncryptedSecretFiles(context.applicationContext.filesDir.toPath(), KeystoreAesGcmCipher())

    override suspend fun put(ref: SecretRef, secret: CharArray): ApiResult<Unit> =
        withContext(Dispatchers.IO) { files.put(ref, secret) }

    override suspend fun get(ref: SecretRef): ApiResult<CharArray> =
        withContext(Dispatchers.IO) { files.get(ref) }

    override suspend fun delete(ref: SecretRef): ApiResult<Unit> =
        withContext(Dispatchers.IO) { files.delete(ref) }
}

internal class EncryptedSecretFiles(
    private val appFilesDirectory: Path,
    private val cipher: SecretCipher,
) {
    private val lock = Any()
    private val rootDirectory = appFilesDirectory.resolve(SECRET_DIRECTORY)

    fun put(ref: SecretRef, secret: CharArray): ApiResult<Unit> = synchronized(lock) {
        val ownedSecret = secret.copyOf()
        var plaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            plaintext = encode(ownedSecret)
            encrypted = cipher.encrypt(plaintext)
            val target = resolve(ref)
            Files.createDirectories(rootDirectory)
            require(!Files.isSymbolicLink(rootDirectory) && rootDirectory.toRealPath().startsWith(
                appFilesDirectory.toRealPath(),
            )) {
                "secret storage directory is unsafe"
            }
            val temporary = Files.createTempFile(rootDirectory, ".secret-", ".tmp")
            try {
                java.nio.channels.FileChannel.open(temporary, WRITE).use { channel ->
                    var buffer = ByteBuffer.wrap(encrypted)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            ApiResult.Success(Unit)
        } catch (failure: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "encrypted secret could not be stored"))
        } catch (failure: GeneralSecurityException) {
            ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "Android Keystore encryption failed"))
        } catch (failure: IllegalArgumentException) {
            ApiResult.Failure(ApiError(ErrorCode.E_AUTH, failure.message ?: "secret reference is invalid"))
        } finally {
            ownedSecret.fill('\u0000')
            plaintext?.fill(0)
            encrypted?.fill(0)
        }
    }

    fun get(ref: SecretRef): ApiResult<CharArray> = synchronized(lock) {
        var encrypted: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            val path = resolve(ref)
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                return@synchronized ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "secret is not available"))
            }
            encrypted = Files.readAllBytes(path)
            plaintext = cipher.decrypt(encrypted)
            ApiResult.Success(decode(plaintext))
        } catch (failure: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "encrypted secret could not be read"))
        } catch (failure: GeneralSecurityException) {
            ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "Android Keystore decryption failed"))
        } catch (failure: IllegalArgumentException) {
            ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "encrypted secret is invalid"))
        } finally {
            encrypted?.fill(0)
            plaintext?.fill(0)
        }
    }

    fun delete(ref: SecretRef): ApiResult<Unit> = synchronized(lock) {
        try {
            val path = resolve(ref)
            if (Files.isSymbolicLink(path)) {
                return@synchronized ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "secret file is unsafe"))
            }
            Files.deleteIfExists(path)
            ApiResult.Success(Unit)
        } catch (failure: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "encrypted secret could not be deleted"))
        } catch (failure: IllegalArgumentException) {
            ApiResult.Failure(ApiError(ErrorCode.E_AUTH, failure.message ?: "secret reference is invalid"))
        }
    }

    private fun resolve(ref: SecretRef): Path {
        require(ref.value.isNotBlank()) { "secret reference is blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ref.value.toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }
        val root = rootDirectory.toAbsolutePath().normalize()
        val path = root.resolve("$digest.bin").normalize()
        require(path.parent == root) { "secret reference resolves outside storage" }
        return path
    }

    private fun encode(secret: CharArray): ByteArray {
        val encoder = UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var encoded: ByteBuffer? = null
        return try {
            encoded = encoder.encode(CharBuffer.wrap(secret))
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            encoded?.let { if (it.hasArray()) it.array().fill(0) }
        }
    }

    private fun decode(plaintext: ByteArray): CharArray {
        val decoder = UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var decoded: CharBuffer? = null
        return try {
            decoded = decoder.decode(ByteBuffer.wrap(plaintext))
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            decoded?.let { if (it.hasArray()) it.array().fill('\u0000') }
        }
    }

    private companion object {
        const val SECRET_DIRECTORY = "keystore-secrets"
    }
}

private class KeystoreAesGcmCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + ciphertext.size).also { output ->
            output[0] = iv.size.toByte()
            System.arraycopy(iv, 0, output, 1, iv.size)
            System.arraycopy(ciphertext, 0, output, 1 + iv.size, ciphertext.size)
            ciphertext.fill(0)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.isEmpty()) throw GeneralSecurityException("encrypted secret is empty")
        val ivLength = ciphertext[0].toInt() and 0xff
        if (ivLength != GCM_IV_BYTES || ciphertext.size <= 1 + ivLength) {
            throw GeneralSecurityException("encrypted secret has an invalid envelope")
        }
        val iv = ciphertext.copyOfRange(1, 1 + ivLength)
        val encryptedPayload = ciphertext.copyOfRange(1 + ivLength, ciphertext.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(encryptedPayload)
            }
        } finally {
            iv.fill(0)
            encryptedPayload.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opencode.secret-store.aes-gcm.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
