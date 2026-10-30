package io.github.spectordev9.opencode.core.connection

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class HttpClients(
    endpoint: Endpoint,
    private val log: ConnectionLog = ConnectionLog(),
    private val sseIdleTimeoutMs: Long = 60_000,
) {
    private val auth = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", endpoint.authorizationHeader)
                .build(),
        )
    }

    private val redacting = Interceptor { chain ->
        val started = System.nanoTime()
        val request = chain.request()
        val response: Response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            log.add("${request.method} ${safeUrl(request.url.toString())} failed: ${error.message.orEmpty()}")
            throw error
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        log.add("${request.method} ${safeUrl(request.url.toString())} -> ${response.code} ${elapsedMs}ms")
        response
    }

    private val base = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(auth)
        .addInterceptor(redacting)
        .build()

    val rest: OkHttpClient = base.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val sse: OkHttpClient = base.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(sseIdleTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun close() {
        rest.dispatcher.executorService.shutdown()
        rest.connectionPool.evictAll()
        sse.dispatcher.executorService.shutdown()
        sse.connectionPool.evictAll()
    }

    private fun safeUrl(value: String): String =
        ErrorMapping.redact(value.substringBefore('?'))
}
