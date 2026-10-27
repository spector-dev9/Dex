package io.github.spectordev9.opencode.server

import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.security.LoopbackCredential
import io.github.spectordev9.opencode.core.security.SecretStore
import io.github.spectordev9.opencode.core.security.impl.SecureLoopbackAuth

/**
 * Owns the per-launch loopback server password (D6): a fresh random secret is
 * provisioned through [SecureLoopbackAuth] on every supervised server start,
 * stored in the Android Keystore-backed [SecretStore], and held in memory only
 * for the duration of the server session.
 */
class LoopbackSecrets(
    secretStore: SecretStore,
    private val secretRef: SecretRef = SecretRef("opencode.loopback.server.password.v1"),
) {
    private val auth = SecureLoopbackAuth(secretStore)

    @Volatile
    private var credential: LoopbackCredential? = null

    /**
     * Headers may be returned from any thread of the supervised server call
     * chain; callers must only read [current] after a successful [provision].
     */
    fun current(): LoopbackCredential? = credential

    private fun clear() {
        credential?.password?.fill('\u0000')
        credential = null
    }

    suspend fun provision(): Result<LoopbackCredential> {
        clear()
        val result = auth.provision(secretRef)
        return when (result) {
            is io.github.spectordev9.opencode.core.common.ApiResult.Success -> {
                credential = result.value
                Result.success(result.value)
            }
            is io.github.spectordev9.opencode.core.common.ApiResult.Failure ->
                Result.failure(IllegalStateException(result.error.message))
        }
    }

    fun authorizationHeader(): String? {
        val current = credential ?: return null
        return auth.authorizationHeader(current)
    }

    /** The password as UTF-8 bytes for the server environment; callers clear it. */
    fun passwordBytes(): ByteArray? {
        val current = credential ?: return null
        return ByteArray(current.password.size) { current.password[it].code.toByte() }
    }
}
