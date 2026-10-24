package io.github.spectordev9.opencode.core.security.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.security.LoopbackAuth
import io.github.spectordev9.opencode.core.security.LoopbackCredential
import io.github.spectordev9.opencode.core.security.SecretStore
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class SecureLoopbackAuth(
    private val secretStore: SecretStore,
) : LoopbackAuth {
    private val secureRandom = SecureRandom()

    override suspend fun provision(secretRef: SecretRef): ApiResult<LoopbackCredential> {
        val randomBytes = ByteArray(SECRET_BYTES)
        secureRandom.nextBytes(randomBytes)
        val encodedPassword = Base64.getUrlEncoder().withoutPadding().encode(randomBytes)
        randomBytes.fill(0)
        val password = CharArray(encodedPassword.size) { encodedPassword[it].toInt().toChar() }
        encodedPassword.fill(0)

        var transferred = false
        val storedPassword = password.copyOf()
        try {
            return when (val stored = secretStore.put(secretRef, storedPassword)) {
                is ApiResult.Success -> {
                    transferred = true
                    ApiResult.Success(LoopbackCredential(USERNAME, password))
                }

                is ApiResult.Failure -> stored
            }
        } finally {
            storedPassword.fill('\u0000')
            if (!transferred) password.fill('\u0000')
        }
    }

    override fun authorizationHeader(credential: LoopbackCredential): String {
        val payload = credentialPayload(credential)
        return try {
            "Basic ${Base64.getEncoder().encodeToString(payload)}"
        } finally {
            payload.fill(0)
        }
    }

    override fun verify(authorizationHeader: String, credential: LoopbackCredential): Boolean {
        val separator = authorizationHeader.indexOf(' ')
        if (separator <= 0 || !authorizationHeader.substring(0, separator).equals("Basic", ignoreCase = true)) {
            return false
        }
        val encoded = authorizationHeader.substring(separator + 1)
        if (encoded.isEmpty() || encoded.any(Char::isWhitespace)) return false

        val supplied = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            val expected = credentialPayload(credential)
            try {
                MessageDigest.isEqual(supplied, expected)
            } finally {
                expected.fill(0)
            }
        } finally {
            supplied.fill(0)
        }
    }

    private fun credentialPayload(credential: LoopbackCredential): ByteArray {
        require(credential.username.isNotBlank() && ':' !in credential.username) {
            "Basic authentication username must be non-empty and cannot contain ':'"
        }

        val usernameBytes = credential.username.toByteArray(UTF_8)
        var passwordBuffer: ByteBuffer? = null
        return try {
            val encodedPassword = UTF_8.newEncoder().encode(CharBuffer.wrap(credential.password))
            passwordBuffer = encodedPassword
            ByteArray(usernameBytes.size + 1 + encodedPassword.remaining()).also { payload ->
                System.arraycopy(usernameBytes, 0, payload, 0, usernameBytes.size)
                payload[usernameBytes.size] = ':'.code.toByte()
                encodedPassword.get(payload, usernameBytes.size + 1, encodedPassword.remaining())
            }
        } finally {
            usernameBytes.fill(0)
            passwordBuffer?.let { if (it.hasArray()) it.array().fill(0) }
        }
    }

    private companion object {
        const val USERNAME = "opencode"
        const val SECRET_BYTES = 32
    }
}
