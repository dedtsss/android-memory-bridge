package net.brucegroup.memorybridge

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.brucegroup.memorybridge.util.IncomingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentContractsInstrumentedTest {
    @Test
    fun process_text_editable_readonly_result_and_share_contracts_work_on_device() {
        val editable = Intent(Intent.ACTION_PROCESS_TEXT)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "device query")
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val readOnly = Intent(Intent.ACTION_PROCESS_TEXT)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "read only")
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared text")

        assertFalse(requireNotNull(IncomingText.processText(editable)).readOnly)
        assertTrue(requireNotNull(IncomingText.processText(readOnly)).readOnly)
        assertEquals("shared text", requireNotNull(IncomingText.sharedText(shared)).text)
        val result = IncomingText.recallResult(
            ApplicationProvider.getApplicationContext(),
            "context from test",
            "device query",
        )
        assertTrue(result.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString().contains("context from test"))
    }
}
