package net.brucegroup.memorybridge

import android.content.ClipboardManager
import net.brucegroup.memorybridge.ui.copyAndFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeUiTest {
    @Test
    fun `copy and return copies context and finishes`() {
        val context = RuntimeEnvironment.getApplication()
        var finished = false

        copyAndFinish(context, "context-pack") { finished = true }

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("context-pack", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(finished)
    }
}
