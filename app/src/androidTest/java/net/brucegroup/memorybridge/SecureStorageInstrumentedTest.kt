package net.brucegroup.memorybridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.brucegroup.memorybridge.data.GatewaySettings
import net.brucegroup.memorybridge.data.GatewaySettingsRepository
import net.brucegroup.memorybridge.data.SecureTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStorageInstrumentedTest {
    @Test
    fun url_and_token_persist_without_plaintext_token_in_preferences() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = "instrumented-token-value"
        val settings = GatewaySettingsRepository(context)
        val secureStore = SecureTokenStore(context)
        secureStore.clear()

        settings.save(GatewaySettings(gatewayUrl = "https://gateway.example", maxContextTokens = 1400))
        secureStore.save(token)

        assertEquals("https://gateway.example", settings.read().gatewayUrl)
        assertEquals(token, secureStore.read())
        val stored = context.getSharedPreferences("memory_bridge_secret", android.content.Context.MODE_PRIVATE)
            .getString("encrypted_token", "").orEmpty()
        assertFalse(stored.contains(token))
        secureStore.clear()
        assertFalse(secureStore.hasToken())
    }
}
