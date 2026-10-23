package io.github.spectordev9.opencode.core.security

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.SecretRef

interface SecretStore {
    suspend fun put(ref: SecretRef, secret: CharArray): ApiResult<Unit>

    suspend fun get(ref: SecretRef): ApiResult<CharArray>

    suspend fun delete(ref: SecretRef): ApiResult<Unit>
}

data class LoopbackCredential(
    val username: String,
    val password: CharArray,
)

interface LoopbackAuth {
    suspend fun provision(secretRef: SecretRef): ApiResult<LoopbackCredential>

    fun authorizationHeader(credential: LoopbackCredential): String

    fun verify(authorizationHeader: String, credential: LoopbackCredential): Boolean
}

fun interface LogScrubber {
    fun scrub(value: String): String
}
