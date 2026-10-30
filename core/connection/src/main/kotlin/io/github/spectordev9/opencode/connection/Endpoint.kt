package io.github.spectordev9.opencode.connection

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

class Endpoint(
    val port: Int,
    password: String,
    val username: String = USERNAME,
) {
    private val password: String = password
    val baseUrl: HttpUrl = "http://$LOOPBACK:$port/".toHttpUrl()
    internal val authorizationHeader: String =
        Credentials.basic(username, password, Charsets.UTF_8)

    init {
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(this.password.isNotEmpty()) { "server password must not be empty" }
        require(username == USERNAME) { "unsupported server username" }
    }

    fun url(path: String, directory: String? = null, workspace: String? = null): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments(path.trimStart('/'))
            .apply {
                directory?.let { addQueryParameter("directory", it) }
                workspace?.let { addQueryParameter("workspace", it) }
            }
            .build()

    fun serverEnvironment(base: Map<String, String> = emptyMap()): Map<String, String> =
        base + mapOf(PASSWORD_ENV to password)

    override fun toString(): String = "Endpoint($baseUrl)"

    companion object {
        const val LOOPBACK: String = "127.0.0.1"
        const val USERNAME: String = "opencode"
        const val PASSWORD_ENV: String = "OPENCODE_SERVER_PASSWORD"

        fun randomPassword(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun reserveLoopbackPort(): Int =
            ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
    }
}
