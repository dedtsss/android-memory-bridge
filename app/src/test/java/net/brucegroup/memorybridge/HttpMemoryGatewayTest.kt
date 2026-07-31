package net.brucegroup.memorybridge

import kotlinx.coroutines.runBlocking
import net.brucegroup.memorybridge.data.HttpMemoryGateway
import net.brucegroup.memorybridge.domain.GatewayException
import net.brucegroup.memorybridge.domain.RecallRequest
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SourceInfo
import net.brucegroup.memorybridge.domain.TextLimits
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpMemoryGatewayTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }
    private fun gateway(token: String = "test-token", readTimeoutMs: Int = 5_000): HttpMemoryGateway =
        HttpMemoryGateway(server.url("/").toString().trimEnd('/'), token, 500, readTimeoutMs)

    @Test
    fun `save sends selected text note and request id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"saved","operation_id":"op-1","title":"A"}"""))
        val response = gateway().save(SaveRequest("exact selected text", "why", SourceInfo(action = "process_text", sourceApp = "example.app")))
        val request = requireNotNull(server.takeRequest())
        val body = JSONObject(request.body.readUtf8())

        assertEquals("POST", request.method)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("X-Request-Id"))
        assertEquals("exact selected text", body.getString("text"))
        assertEquals("why", body.getString("note"))
        assertEquals("op-1", response.operationId)
    }

    @Test
    fun `recall uses Gateway context exactly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"context":"packed context","truncated":false}"""))
        val result = gateway().recall(RecallRequest("question", 1200, SourceInfo(action = "process_text")))
        assertEquals("packed context", result.context)
    }

    @Test
    fun `http status errors are classified and token never appears in error`() = runBlocking {
        for (status in listOf(401, 403, 404, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("server text must not be shown"))
            try {
                gateway("token-that-must-not-leak").health()
                throw AssertionError("Expected HTTP failure")
            } catch (error: GatewayException.Http) {
                assertEquals(status, error.statusCode)
                assertFalse(error.message.orEmpty().contains("token-that-must-not-leak"))
                assertNotNull(error.requestId)
            }
        }
    }

    @Test
    fun `offline timeout and oversized response are safe failures`() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        try {
            gateway(readTimeoutMs = 20).health()
            throw AssertionError("Expected timeout")
        } catch (error: GatewayException.Timeout) {
            assertNotNull(error.requestId)
        }

        server.enqueue(MockResponse().setResponseCode(200).setBody("{" + "x".repeat(TextLimits.MAX_RESPONSE_CHARS * 4) + "}"))
        try {
            gateway().health()
            throw AssertionError("Expected response limit failure")
        } catch (error: GatewayException.ResponseTooLarge) {
            assertNotNull(error.requestId)
        }
    }

    @Test
    fun `offline connection is classified separately`() = runBlocking {
        val offline = HttpMemoryGateway("http://127.0.0.1:1", "token", 50, 50)
        try {
            offline.health()
            throw AssertionError("Expected offline failure")
        } catch (error: GatewayException.Offline) {
            assertNotNull(error.requestId)
        }
    }
}
