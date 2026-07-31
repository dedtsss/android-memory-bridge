package net.brucegroup.memorybridge

import android.content.Intent
import net.brucegroup.memorybridge.domain.TextLimits
import net.brucegroup.memorybridge.util.IncomingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingTextTest {
    @Test
    fun `process text preserves the exact text and reads editable flag`() {
        val text = "A precise\nselected fragment"
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val parsed = requireNotNull(IncomingText.processText(intent))

        assertEquals(text, parsed.text)
        assertFalse(parsed.readOnly)
    }

    @Test
    fun `process text defaults to read only and rejects empty text`() {
        val readOnly = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "question")
        assertTrue(requireNotNull(IncomingText.processText(readOnly)).readOnly)
        assertNull(IncomingText.processText(Intent(Intent.ACTION_PROCESS_TEXT)))
    }

    @Test
    fun `recall result returns protocol extra with original query`() {
        val app = RuntimeEnvironment.getApplication()
        val result = IncomingText.recallResult(app, "recalled context", "my query")

        val replacement = result.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString()
        assertTrue(replacement.contains("recalled context"))
        assertTrue(replacement.contains("my query"))
    }

    @Test
    fun `share accepts text plain and marks standalone urls`() {
        val url = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://chatgpt.com/share/abc")
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "full conversation")

        assertTrue(requireNotNull(IncomingText.sharedText(url)).isUrlOnly)
        assertFalse(requireNotNull(IncomingText.sharedText(text)).isUrlOnly)
        assertNull(IncomingText.sharedText(Intent(Intent.ACTION_SEND).setType("image/png")))
    }

    @Test
    fun `input limit rejects text without truncating it`() {
        val oversized = "x".repeat(TextLimits.MAX_INPUT_CHARS + 1)
        assertFalse(IncomingText.isSafeInput(oversized))
        assertEquals(TextLimits.MAX_INPUT_CHARS + 1, oversized.length)
    }
}
