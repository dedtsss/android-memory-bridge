package net.brucegroup.memorybridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.brucegroup.memorybridge.data.GatewayFactory
import net.brucegroup.memorybridge.data.GatewaySettings
import net.brucegroup.memorybridge.data.GatewaySettingsRepository
import net.brucegroup.memorybridge.data.SecureTokenStore
import net.brucegroup.memorybridge.domain.TextLimits
import net.brucegroup.memorybridge.ui.BridgePage
import net.brucegroup.memorybridge.ui.MemoryBridgeTheme
import net.brucegroup.memorybridge.ui.gatewayMessage

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: GatewaySettingsRepository
    private lateinit var tokenStore: SecureTokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = GatewaySettingsRepository(this)
        tokenStore = SecureTokenStore(this)
        setContent {
            MemoryBridgeTheme {
                var settings: GatewaySettings by remember { mutableStateOf(settingsRepository.read()) }
                var url: String by remember(settings.gatewayUrl) { mutableStateOf(settings.gatewayUrl) }
                var tokenInput: String by remember { mutableStateOf("") }
                var tokenStored: Boolean by remember { mutableStateOf(tokenStore.hasToken()) }
                var maxTokens: String by remember(settings.maxContextTokens) { mutableStateOf(settings.maxContextTokens.toString()) }
                var connectionState: String? by remember { mutableStateOf(null) }
                var checking: Boolean by remember { mutableStateOf(false) }

                fun saveSettings(): GatewaySettings {
                    val updated = settings.copy(
                        gatewayUrl = url.trim(),
                        maxContextTokens = maxTokens.toIntOrNull()?.let(TextLimits::clampContextTokens)
                            ?: settings.maxContextTokens,
                    )
                    settingsRepository.save(updated)
                    if (tokenInput.isNotBlank()) {
                        tokenStore.save(tokenInput)
                        tokenInput = ""
                        tokenStored = true
                    }
                    settings = settingsRepository.read()
                    return settings
                }

                BridgePage(getString(R.string.settings_title)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(getString(R.string.gateway_url)) },
                        supportingText = {
                            Text(
                                if (BuildConfig.DEBUG) getString(R.string.gateway_debug_hint, GatewayFactory.DEBUG_FAKE_URL)
                                else getString(R.string.gateway_https_hint),
                            )
                        },
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(getString(R.string.api_token)) },
                        placeholder = { Text(if (tokenStored) getString(R.string.token_saved) else "") },
                    )
                    if (tokenStored) {
                        OutlinedButton(onClick = {
                            tokenStore.clear()
                            tokenStored = false
                            tokenInput = ""
                        }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.delete_token)) }
                    }
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(getString(R.string.max_context_tokens)) },
                    )
                    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.confirm_before_save))
                        Switch(
                            checked = settings.confirmBeforeSave,
                            onCheckedChange = {
                                settings = settings.copy(confirmBeforeSave = it)
                                settingsRepository.save(settings)
                            },
                        )
                    }
                    Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.save)) }
                    Button(
                        enabled = !checking,
                        onClick = {
                            val current = saveSettings()
                            checking = true
                            connectionState = null
                            lifecycleScope.launch {
                                try {
                                    GatewayFactory.create(current, tokenStore.read()).health()
                                    connectionState = getString(R.string.connection_ok)
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    connectionState = gatewayMessage(this@MainActivity, error)
                                } finally {
                                    checking = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(getString(R.string.test_connection)) }
                    Text(connectionState ?: getString(R.string.connection_unknown))
                    Text(getString(R.string.last_operation))
                    val operationId = settings.lastOperationId
                    if (operationId == null) {
                        Text(getString(R.string.no_last_operation))
                    } else {
                        Text(settings.lastOperationLabel.orEmpty())
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                lifecycleScope.launch {
                                    try {
                                        GatewayFactory.create(settings, tokenStore.read()).undo(operationId)
                                        settingsRepository.clearUndo()
                                        settings = settingsRepository.read()
                                        connectionState = getString(R.string.undo_complete)
                                    } catch (error: Throwable) {
                                        if (error is CancellationException) throw error
                                        connectionState = gatewayMessage(this@MainActivity, error)
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(getString(R.string.undo)) }
                    }
                    Text(getString(R.string.version, BuildConfig.VERSION_NAME))
                }
            }
        }
    }
}
