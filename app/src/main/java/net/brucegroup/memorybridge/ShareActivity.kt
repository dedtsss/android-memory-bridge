package net.brucegroup.memorybridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SaveResponse
import net.brucegroup.memorybridge.domain.SourceInfo
import net.brucegroup.memorybridge.domain.TextLimits
import net.brucegroup.memorybridge.ui.BridgePage
import net.brucegroup.memorybridge.ui.BusyIndicator
import net.brucegroup.memorybridge.ui.MemoryBridgeTheme
import net.brucegroup.memorybridge.ui.TextCard
import net.brucegroup.memorybridge.ui.copyToClipboard
import net.brucegroup.memorybridge.ui.gatewayMessage
import net.brucegroup.memorybridge.ui.openChatGpt
import net.brucegroup.memorybridge.util.IncomingText
import net.brucegroup.memorybridge.util.SharedTextInput

private sealed interface ShareUiState {
    data object Prompt : ShareUiState
    data object Loading : ShareUiState
    data class Saved(val response: SaveResponse, val message: String? = null) : ShareUiState
    data class RecallResult(val context: String) : ShareUiState
    data class Error(val message: String) : ShareUiState
}

class ShareActivity : ComponentActivity() {
    private lateinit var settingsRepository: GatewaySettingsRepository
    private lateinit var tokenStore: SecureTokenStore
    private var input: SharedTextInput? = null
    private var state by mutableStateOf<ShareUiState>(ShareUiState.Prompt)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = GatewaySettingsRepository(this)
        tokenStore = SecureTokenStore(this)
        input = IncomingText.sharedText(intent)
        if (input == null) state = ShareUiState.Error(getString(R.string.empty_text_error))
        else if (!TextLimits.validateInput(input!!.text)) state = ShareUiState.Error(getString(R.string.text_too_long, TextLimits.MAX_INPUT_CHARS))
        setContent {
            MemoryBridgeTheme {
                val shared = input
                BridgePage(getString(R.string.app_name)) {
                    when (val current = state) {
                        ShareUiState.Prompt -> if (shared != null) SharePrompt(shared, ::save, ::recall, ::finish)
                        ShareUiState.Loading -> BusyIndicator()
                        is ShareUiState.Saved -> ShareSaved(current, ::undo, ::finish)
                        is ShareUiState.RecallResult -> ShareRecallResult(current.context, ::finish)
                        is ShareUiState.Error -> ErrorScreen(current.message, ::finish)
                    }
                }
            }
        }
    }

    private fun save(note: String?) {
        if (state == ShareUiState.Loading) return
        val text = input?.text ?: return
        state = ShareUiState.Loading
        lifecycleScope.launch {
            try {
                val response = GatewayFactory.create(settingsRepository.read(), tokenStore.read()).save(
                    SaveRequest(text, note, SourceInfo(action = "share", sourceApp = callingPackage)),
                )
                settingsRepository.rememberUndo(response.operationId, response.title ?: response.project)
                state = ShareUiState.Saved(response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                state = ShareUiState.Error(gatewayMessage(this@ShareActivity, error))
            }
        }
    }

    private fun recall() {
        if (state == ShareUiState.Loading) return
        val text = input?.text ?: return
        state = ShareUiState.Loading
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.read()
                val response = GatewayFactory.create(settings, tokenStore.read()).recall(
                    RecallRequest(text, settings.maxContextTokens, SourceInfo(action = "share", sourceApp = callingPackage)),
                )
                state = ShareUiState.RecallResult(response.context)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                state = ShareUiState.Error(gatewayMessage(this@ShareActivity, error))
            }
        }
    }

    private fun undo(response: SaveResponse) {
        val operationId = response.operationId ?: return
        state = ShareUiState.Loading
        lifecycleScope.launch {
            try {
                GatewayFactory.create(settingsRepository.read(), tokenStore.read()).undo(operationId)
                settingsRepository.clearUndo()
                state = ShareUiState.Saved(response.copy(operationId = null), getString(R.string.undo_complete))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                state = ShareUiState.Error(gatewayMessage(this@ShareActivity, error))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SharePrompt(
    input: SharedTextInput,
    onSave: (String?) -> Unit,
    onRecall: () -> Unit,
    onClose: () -> Unit,
) {
    var note by androidx.compose.runtime.remember { mutableStateOf("") }
    Text(androidx.compose.ui.res.stringResource(R.string.shared_text))
    TextCard(input.text)
    if (input.isUrlOnly) Text(androidx.compose.ui.res.stringResource(R.string.share_url_notice))
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(androidx.compose.ui.res.stringResource(R.string.note)) },
    )
    Button(onClick = { onSave(note) }, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.saving_to_memory))
    }
    OutlinedButton(onClick = onRecall, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.recall_optional))
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.cancel))
    }
}

@androidx.compose.runtime.Composable
private fun ShareSaved(state: ShareUiState.Saved, onUndo: (SaveResponse) -> Unit, onClose: () -> Unit) {
    Text(androidx.compose.ui.res.stringResource(R.string.saved_to_memory))
    state.response.title?.let { Text(it) }
    state.response.project?.let { Text(it) }
    state.message?.let { Text(it) }
    state.response.operationId?.let {
        Button(onClick = { onUndo(state.response) }, modifier = Modifier.fillMaxWidth()) {
            Text(androidx.compose.ui.res.stringResource(R.string.undo))
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(R.string.close)) }
}

@androidx.compose.runtime.Composable
private fun ShareRecallResult(contextPack: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    TextCard(contextPack)
    Button(onClick = { copyToClipboard(context, contextPack) }, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.copy))
    }
    Button(onClick = {
        copyToClipboard(context, contextPack)
        openChatGpt(context)
    }, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.copy_and_open_chatgpt))
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.close))
    }
}
