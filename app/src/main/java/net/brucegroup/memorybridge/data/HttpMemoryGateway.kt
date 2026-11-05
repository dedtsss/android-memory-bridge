package net.brucegroup.memorybridge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.brucegroup.memorybridge.BuildConfig
import net.brucegroup.memorybridge.domain.GatewayException
import net.brucegroup.memorybridge.domain.MemoryGateway
import net.brucegroup.memorybridge.domain.RecallRequest
import net.brucegroup.memorybridge.domain.RecallResponse
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SaveResponse
import net.brucegroup.memorybridge.domain.SourceInfo
import net.brucegroup.memorybridge.domain.TextLimits
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class HttpMemoryGateway(
    private val baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : MemoryGateway {
    override suspend fun health() {
        request("GET", "/v1/health", null)
    }

    override suspend fun save(request: SaveRequest): SaveResponse {
        val response = request("POST", "/v1/memory/save", JSONObject().apply {
            put("text", request.text)
            request.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            put("source", request.source.toJson())
        })
        val json = response.toJson()
        if (json.optString("status") != "saved") throw GatewayException.Protocol()
        return SaveResponse(
            memoryId = json.optNullableString("memory_id"),
            operationId = json.optNullableString("operation_id"),
            title = json.optNullableString("title"),
            project = json.optNullableString("project"),
        )
    }

    override suspend fun recall(request: RecallRequest): RecallResponse {
        val response = request("POST", "/v1/memory/recall", JSONObject().apply {
            put("query", request.query)
            put("max_tokens", TextLimits.clampContextTokens(request.maxTokens))
            put("source", request.source.toJson())
        })
        val json = response.toJson()
        val context = json.optNullableString("context") ?: throw GatewayException.Protocol()
        if (context.length > TextLimits.MAX_RESPONSE_CHARS) throw GatewayException.ResponseTooLarge()
        return RecallResponse(
            context = context,
            project = json.optNullableString("project"),
            truncated = json.optBoolean("truncated", false),
        )
    }

    override suspend fun undo(operationId: String) {
        request("POST", "/v1/memory/undo", JSONObject().put("operation_id", operationId))
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): String = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString()
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
        }
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            try {
                if (!continuation.isActive) return@launch
                val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
                activeConnection.set(connection)
                if (!continuation.isActive) return@launch
                connection.apply {
                    requestMethod = method
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doInput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Request-Id", requestId)
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = try {
                    connection.responseCode
                } catch (error: SocketTimeoutException) {
                    throw GatewayException.Timeout(error, requestId)
                } catch (error: IOException) {
                    throw GatewayException.Offline(error, requestId)
                }
                if (code !in 200..299) throw GatewayException.Http(code, requestId)
                val response = try {
                    connection.inputStream.use { readBounded(it, requestId) }
                } catch (error: SocketTimeoutException) {
                    throw GatewayException.Timeout(error, requestId)
                } catch (error: IOException) {
                    throw GatewayException.Offline(error, requestId)
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(response))
            } catch (error: SocketTimeoutException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(GatewayException.Timeout(error, requestId)))
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(GatewayException.Offline(error, requestId)))
            } catch (error: GatewayException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, requestId: String): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > TextLimits.MAX_RESPONSE_CHARS * 4) {
                throw GatewayException.ResponseTooLarge(requestId)
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun String.toJson(): JSONObject = try {
        JSONObject(this)
    } catch (_: Exception) {
        throw GatewayException.Protocol()
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun SourceInfo.toJson(): JSONObject = JSONObject().apply {
        put("platform", platform)
        put("action", action)
        sourceApp?.let { put("source_app", it) }
    }
}

object GatewayFactory {
    const val DEBUG_FAKE_URL = "mock://memory-gateway"
    private val fakeGateway = FakeMemoryGateway()

    fun create(settings: GatewaySettings, token: String?): MemoryGateway {
        val url = settings.gatewayUrl.trim()
        if (BuildConfig.DEBUG && url == DEBUG_FAKE_URL) return fakeGateway
        validateUrl(url)
        if (token.isNullOrBlank()) throw GatewayException.Configuration("Missing API token")
        return HttpMemoryGateway(url, token)
    }

    fun validateUrl(value: String) {
        val uri = try { URI(value) } catch (_: Exception) { throw GatewayException.Configuration("Invalid Gateway URL") }
        val host = uri.host ?: throw GatewayException.Configuration("Invalid Gateway URL")
        val localDebugHost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1"
        val allowed = uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && localDebugHost)
        if (!allowed) throw GatewayException.Configuration("HTTPS is required")
    }
}

/** Development and test-only deterministic implementation; it is never an external backend. */
class FakeMemoryGateway : MemoryGateway {
    var nextFailure: GatewayException? = null
    var lastSave: SaveRequest? = null
    var lastRecall: RecallRequest? = null
    var undoneOperation: String? = null

    private fun failIfNeeded() { nextFailure?.let { throw it } }
    override suspend fun health() = failIfNeeded()
    override suspend fun save(request: SaveRequest): SaveResponse {
        failIfNeeded()
        lastSave = request
        return SaveResponse("fake-memory", "fake-operation", "Mock memory", "Development")
    }
    override suspend fun recall(request: RecallRequest): RecallResponse {
        failIfNeeded()
        lastRecall = request
        return RecallResponse("Mock context-pack for: ${request.query}", "Development", false)
    }
    override suspend fun undo(operationId: String) {
        failIfNeeded()
        undoneOperation = operationId
    }
}
