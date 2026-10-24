package io.github.spectordev9.opencode.core.security.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.security.LoopbackCredential
import io.github.spectordev9.opencode.core.security.SecretStore
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureLoopbackAuthTest {
    @Test
    fun `provisions independent per-launch passwords and persists each password`() = runBlocking {
        val store = InMemorySecretStore()
        val auth = SecureLoopbackAuth(store)

        val first = assertIs<ApiResult.Success<LoopbackCredential>>(
            auth.provision(SecretRef("launch-1")),
        ).value
        val second = assertIs<ApiResult.Success<LoopbackCredential>>(
            auth.provision(SecretRef("launch-2")),
        ).value

        assertEquals("opencode", first.username)
        assertEquals(43, first.password.size)
        assertTrue(first.password.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(first.password.concatToString(), second.password.concatToString())
        assertContentEquals(first.password, assertIs<ApiResult.Success<CharArray>>(store.get(SecretRef("launch-1"))).value)
        assertContentEquals(second.password, assertIs<ApiResult.Success<CharArray>>(store.get(SecretRef("launch-2"))).value)
    }

    @Test
    fun `authorization header uses Basic auth and verifies only the matching credential`() = runBlocking {
        val auth = SecureLoopbackAuth(InMemorySecretStore())
        val credential = assertIs<ApiResult.Success<LoopbackCredential>>(
            auth.provision(SecretRef("launch")),
        ).value
        val header = auth.authorizationHeader(credential)
        val payload = Base64.getDecoder().decode(header.removePrefix("Basic "))

        assertEquals("opencode:${credential.password.concatToString()}", payload.toString(Charsets.UTF_8))
        assertTrue(auth.verify(header, credential))
        assertTrue(auth.verify(header.replaceFirst("Basic", "basic"), credential))
        assertFalse(auth.verify(header, credential.copy(password = "different".toCharArray())))
    }

    @Test
    fun `rejects malformed or non-Basic authorization headers`() = runBlocking {
        val auth = SecureLoopbackAuth(InMemorySecretStore())
        val credential = assertIs<ApiResult.Success<LoopbackCredential>>(
            auth.provision(SecretRef("launch")),
        ).value

        listOf("", "Bearer abc", "Basic", "Basic not-base64", "Basic  YWJj", "Basic YWJj ").forEach { header ->
            assertFalse(auth.verify(header, credential), header)
        }
    }

    @Test
    fun `surfaces secret store failures without returning a credential`() = runBlocking {
        val failure = ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "keystore unavailable"))
        val auth = SecureLoopbackAuth(InMemorySecretStore(failure))

        assertEquals(failure, auth.provision(SecretRef("launch")))
    }

    @Test
    fun `rejects usernames that cannot be represented safely in Basic auth`() {
        val auth = SecureLoopbackAuth(InMemorySecretStore())

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            auth.authorizationHeader(LoopbackCredential("bad:user", "password".toCharArray()))
        }
    }

    private class InMemorySecretStore(
        private val putResult: ApiResult<Unit> = ApiResult.Success(Unit),
    ) : SecretStore {
        private val values = mutableMapOf<String, CharArray>()

        override suspend fun put(ref: SecretRef, secret: CharArray): ApiResult<Unit> {
            if (putResult is ApiResult.Success) values[ref.value] = secret.copyOf()
            return putResult
        }

        override suspend fun get(ref: SecretRef): ApiResult<CharArray> =
            values[ref.value]?.copyOf()?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "secret not found"))

        override suspend fun delete(ref: SecretRef): ApiResult<Unit> {
            values.remove(ref.value)?.fill('\u0000')
            return ApiResult.Success(Unit)
        }
    }
}
