package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.app.security.AppLoopbackAuth
import io.github.spectordev9.opencode.app.security.InMemorySecretStore
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlin.test.Test
import kotlin.test.assertTrue

class AuthHealthTest {
    @Test
    fun `provisioned client can be created and health check is callable` = runBlocking {
        // 1️⃣  Set up a minimal secret store and auth implementation
        val store = InMemorySecretStore()
        val auth = AppLoopbackAuth(store)

        // 2️⃣  Provision a password – this returns a LoopbackCredential
        val prov = auth.provision(io.github.spectordev9.opencode.core.common.SecretRef("g1-auth"))
        assertTrue(prov.isSuccess) { "provision must succeed" }

        // 3️⃣  Extract the password and build the authenticated client
        val password = prov.value?.password ?: runFatalError("no password")
        val client = io.github.spectordev9.opencode.core.opencode.client.OpenCodeHttpClient(
            baseUrl = "http://127.0.0.1:0",   // port 0 means “unused”
            password = password,
        )

        // 4️⃣  Call health – the client will hit the mock‑server‑less path,
        //     but the important thing is that *no exception* is thrown
        //     and the result type is ApiResult<GatewayHealth>.
        val health: ApiResult<io.github.spectordev9.opencode.core.gateway.GatewayHealth> =
            client.health()

        // 5️⃣  The call must succeed (the underlying implementation returns a
        //     minimal health object even when no server is listening).
        assertTrue(health.isSuccess) { "health check must not fail" }
    }
}