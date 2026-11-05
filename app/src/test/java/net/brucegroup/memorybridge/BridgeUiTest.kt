package net.brucegroup.memorybridge

import android.content.ClipboardManager
import net.brucegroup.memorybridge.ui.copyAndFinish
import net.brucegroup.memorybridge.ui.gatewayMessage
import net.brucegroup.memorybridge.domain.GatewayException
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

    @Test
    fun `debug configuration diagnostics distinguish safe known failures`() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals("Invalid Gateway URL.", gatewayMessage(context, GatewayException.Configuration("Invalid Gateway URL")))
        assertEquals("Missing API token.", gatewayMessage(context, GatewayException.Configuration("Missing API token")))
    }

    @Test
    fun `debug configuration diagnostics do not expose arbitrary details`() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            "Check the Gateway URL and API token.",
            gatewayMessage(context, GatewayException.Configuration("secret-like arbitrary detail")),
        )
    }
}
