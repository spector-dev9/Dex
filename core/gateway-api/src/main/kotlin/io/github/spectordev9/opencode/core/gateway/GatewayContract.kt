package io.github.spectordev9.opencode.core.gateway

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.TimeoutClass
import kotlinx.coroutines.flow.Flow

enum class HttpMethod {
    GET,
    POST,
    PATCH,
    PUT,
    DELETE,
}

data class GatewayRequest(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
) {
    init {
        require(path.startsWith("/")) { "path must start with /" }
    }
}

fun interface GatewayResponseDecoder<T> {
    fun decode(statusCode: Int, body: ByteArray): T
}

data class GatewayOperation<T>(
    val name: String,
    val request: GatewayRequest,
    val timeoutClass: TimeoutClass,
    val decoder: GatewayResponseDecoder<T>,
)

data class GatewayHealth(
    val version: String,
    val endpointPort: Int,
)

data class ServerEvent(
    val type: String,
    val directory: String? = null,
    val sequence: Long? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val id: String? = null,
)

interface OpenCodeGateway {
    suspend fun <T> call(operation: GatewayOperation<T>): ApiResult<T>

    fun events(directory: String? = null): Flow<ServerEvent>

    suspend fun health(): ApiResult<GatewayHealth>
}
