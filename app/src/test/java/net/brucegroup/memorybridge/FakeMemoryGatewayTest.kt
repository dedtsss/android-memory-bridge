package net.brucegroup.memorybridge

import kotlinx.coroutines.runBlocking
import net.brucegroup.memorybridge.data.FakeMemoryGateway
import net.brucegroup.memorybridge.domain.RecallRequest
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
