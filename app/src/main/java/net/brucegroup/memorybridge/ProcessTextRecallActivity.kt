package net.brucegroup.memorybridge

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.brucegroup.memorybridge.data.GatewayFactory
import net.brucegroup.memorybridge.data.GatewaySettingsRepository
import net.brucegroup.memorybridge.data.SecureTokenStore
import net.brucegroup.memorybridge.domain.RecallRequest
import net.brucegroup.memorybridge.domain.SourceInfo
import net.brucegroup.memorybridge.domain.TextLimits
import net.brucegroup.memorybridge.ui.BridgePage
import net.brucegroup.memorybridge.ui.BusyIndicator
import net.brucegroup.memorybridge.ui.MemoryBridgeTheme
import net.brucegroup.memorybridge.ui.TextCard
import net.brucegroup.memorybridge.ui.copyToClipboard
import net.brucegroup.memorybridge.ui.copyAndFinish
import net.brucegroup.memorybridge.ui.gatewayMessage
import net.brucegroup.memorybridge.util.IncomingText
import net.brucegroup.memorybridge.util.ProcessTextInput

private sealed interface RecallUiState {
    data object Loading : RecallUiState
    data class Result(val context: String) : RecallUiState
    data class Error(val message: String) : RecallUiState
}

class ProcessTextRecallActivity : ComponentActivity() {
    private lateinit var settingsRepository: GatewaySettingsRepository
    private lateinit var tokenStore: SecureTokenStore
    private var state by mutableStateOf<RecallUiState>(RecallUiState.Loading)
    private var input: ProcessTextInput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = GatewaySettingsRepository(this)
        tokenStore = SecureTokenStore(this)
        input = IncomingText.processText(intent)
        setResult(Activity.RESULT_CANCELED)
        if (input == null) {
            state = RecallUiState.Error(getString(R.string.empty_text_error))
        } else if (!TextLimits.validateInput(input!!.text)) {
            state = RecallUiState.Error(getString(R.string.text_too_long, TextLimits.MAX_INPUT_CHARS))
        } else {
            recall()
        }
        setContent {
            MemoryBridgeTheme {
                BridgePage(getString(R.string.recall_from_memory)) {
                    when (val current = state) {
                        RecallUiState.Loading -> BusyIndicator()
                        is RecallUiState.Result -> RecallResultScreen(current.context, ::finish)
                        is RecallUiState.Error -> ErrorScreen(current.message, ::finish)
                    }
                }
            }
        }
    }

    private fun recall() {
        val requestInput = input ?: return
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.read()
                val response = GatewayFactory.create(settings, tokenStore.read()).recall(
                    RecallRequest(requestInput.text, settings.maxContextTokens, SourceInfo(action = "process_text", sourceApp = callingPackage)),
                )
                if (!requestInput.readOnly) {
                    setResult(Activity.RESULT_OK, IncomingText.recallResult(this@ProcessTextRecallActivity, response.context, requestInput.text))
                    finish()
                } else {
                    state = RecallUiState.Result(response.context)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // RESULT_CANCELED preserves the original selection on every error.
                state = RecallUiState.Error(gatewayMessage(this@ProcessTextRecallActivity, error))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecallResultScreen(contextPack: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    TextCard(contextPack)
    Button(onClick = { copyToClipboard(context, contextPack) }, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.copy))
    }
    Button(onClick = {
        copyAndFinish(context, contextPack, onClose)
    }, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.copy_and_return))
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.close))
    }
}
