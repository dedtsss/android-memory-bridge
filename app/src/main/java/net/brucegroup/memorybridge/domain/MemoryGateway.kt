package net.brucegroup.memorybridge.domain

data class SourceInfo(
    val platform: String = "android",
    val action: String,
    val sourceApp: String? = null,
)

data class SaveRequest(
    val text: String,
    val note: String?,
    val source: SourceInfo,
)

data class SaveResponse(
    val memoryId: String?,
    val operationId: String?,
    val title: String?,
    val project: String?,
)

data class RecallRequest(
    val query: String,
    val maxTokens: Int,
    val source: SourceInfo,
)

data class RecallResponse(
    val context: String,
    val project: String?,
    val truncated: Boolean,
)

/** A small backend-neutral API surface. UI depends on this interface only. */
interface MemoryGateway {
    suspend fun health()
    suspend fun save(request: SaveRequest): SaveResponse
    suspend fun recall(request: RecallRequest): RecallResponse
    suspend fun undo(operationId: String)
}

sealed class GatewayException(
    message: String,
    cause: Throwable? = null,
    /** Opaque UUID only; it never contains text, URL, or bearer-token data. */
    val requestId: String? = null,
) : Exception(message, cause) {
    class Configuration(message: String) : GatewayException(message)
    class Http(val statusCode: Int, requestId: String? = null) : GatewayException("HTTP $statusCode", requestId = requestId)
    class Offline(cause: Throwable? = null, requestId: String? = null) : GatewayException("Network unavailable", cause, requestId)
    class Timeout(cause: Throwable? = null, requestId: String? = null) : GatewayException("Network timeout", cause, requestId)
    class ResponseTooLarge(requestId: String? = null) : GatewayException("Response exceeds safe limit", requestId = requestId)
    class Protocol(requestId: String? = null) : GatewayException("Gateway returned an invalid response", requestId = requestId)
}

object TextLimits {
    const val MAX_INPUT_CHARS = 20_000
    const val MAX_RESPONSE_CHARS = 60_000
    const val MIN_CONTEXT_TOKENS = 100
    const val MAX_CONTEXT_TOKENS = 8_000

    fun validateInput(text: String): Boolean = text.isNotBlank() && text.length <= MAX_INPUT_CHARS
    fun clampContextTokens(value: Int): Int = value.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
}
