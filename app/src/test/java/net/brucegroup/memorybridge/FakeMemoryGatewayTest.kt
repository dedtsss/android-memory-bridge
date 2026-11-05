package net.brucegroup.memorybridge

import kotlinx.coroutines.runBlocking
import net.brucegroup.memorybridge.data.GatewayFactory
import net.brucegroup.memorybridge.data.GatewaySettings
import net.brucegroup.memorybridge.data.GatewaySettingsRepository
import net.brucegroup.memorybridge.data.FakeMemoryGateway
import net.brucegroup.memorybridge.domain.GatewayException
import net.brucegroup.memorybridge.domain.RecallRequest
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeMemoryGatewayTest {
    @Test
    fun `fake supports successful save recall and undo`() = runBlocking {
        val gateway = FakeMemoryGateway()
        val save = gateway.save(SaveRequest("selected", "note", SourceInfo(action = "process_text")))
        val recall = gateway.recall(RecallRequest("selected", 1200, SourceInfo(action = "process_text")))
        gateway.undo(requireNotNull(save.operationId))

        assertEquals("selected", gateway.lastSave?.text)
        assertEquals("selected", gateway.lastRecall?.query)
        assertEquals("fake-operation", gateway.undoneOperation)
        assertEquals("Mock context-pack for: selected", recall.context)
    }

    @Test
    fun `saved debug fake URL creates healthy gateway without token`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = GatewaySettingsRepository(context)
        repository.save(GatewaySettings(gatewayUrl = "  ${GatewayFactory.DEBUG_FAKE_URL}\n"))

        val savedSettings = repository.read()
        listOf<String?>(null, "", "   ").forEach { token ->
            GatewayFactory.create(savedSettings, token).health()
        }

        assertEquals(GatewayFactory.DEBUG_FAKE_URL, savedSettings.gatewayUrl)
    }

    @Test
    fun `debug fake URL matching remains exact`() {
        listOf("mock://memory-gateway/", "MOCK://memory-gateway", "mock://other-gateway").forEach { url ->
            assertThrows(GatewayException.Configuration::class.java) {
                GatewayFactory.create(GatewaySettings(gatewayUrl = url), null)
            }
        }
    }
}
