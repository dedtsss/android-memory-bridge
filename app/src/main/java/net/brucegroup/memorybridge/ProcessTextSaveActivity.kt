package net.brucegroup.memorybridge

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.brucegroup.memorybridge.data.GatewayFactory
import net.brucegroup.memorybridge.data.GatewaySettingsRepository
import net.brucegroup.memorybridge.data.SecureTokenStore
import net.brucegroup.memorybridge.domain.SaveRequest
import net.brucegroup.memorybridge.domain.SaveResponse
import net.brucegroup.memorybridge.domain.SourceInfo
import net.brucegroup.memorybridge.domain.TextLimits
import net.brucegroup.memorybridge.ui.BridgePage
import net.brucegroup.memorybridge.ui.BusyIndicator
import net.brucegroup.memorybridge.ui.MemoryBridgeTheme
import net.brucegroup.memorybridge.ui.PrimaryAndCancel
import net.brucegroup.memorybridge.ui.TextCard
import net.brucegroup.memorybridge.ui.gatewayMessage
import net.brucegroup.memorybridge.util.IncomingText
import net.brucegroup.memorybridge.util.ProcessTextInput

private sealed interface SaveUiState {
    data object Prompt : SaveUiState
    data object Loading : SaveUiState
    data class Saved(val response: SaveResponse, val message: String? = null) : SaveUiState
    data class Error(val message: String) : SaveUiState
}

class ProcessTextSaveActivity : ComponentActivity() {
    private lateinit var settingsRepository: GatewaySettingsRepository
    private lateinit var tokenStore: SecureTokenStore
    private var state by mutableStateOf<SaveUiState>(SaveUiState.Prompt)
    private var input: ProcessTextInput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = GatewaySettingsRepository(this)
        tokenStore = SecureTokenStore(this)
        input = IncomingText.processText(intent)
        setResult(Activity.RESULT_CANCELED)
        when {
            input == null -> state = SaveUiState.Error(getString(R.string.empty_text_error))
            !TextLimits.validateInput(input!!.text) -> state = SaveUiState.Error(getString(R.string.text_too_long, TextLimits.MAX_INPUT_CHARS))
            !settingsRepository.read().confirmBeforeSave -> save(null)
        }
        setContent {
            MemoryBridgeTheme {
                val currentInput = input
                BridgePage(getString(R.string.saving_to_memory)) {
                    when (val current = state) {
                        SaveUiState.Prompt -> if (currentInput != null) SavePrompt(currentInput.text, ::save, ::finish)
                        SaveUiState.Loading -> BusyIndicator()
                        is SaveUiState.Saved -> SavedScreen(current, ::undo, ::finish)
                        is SaveUiState.Error -> ErrorScreen(current.message, ::finish)
                    }
                }
            }
        }
    }

    private fun save(note: String?) {
        if (state == SaveUiState.Loading) return
        val text = input?.text ?: return
        state = SaveUiState.Loading
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.read()
                val response = GatewayFactory.create(settings, tokenStore.read()).save(
                    SaveRequest(text, note, SourceInfo(action = "process_text", sourceApp = callingPackage)),
                )
                settingsRepository.rememberUndo(response.operationId, response.title ?: response.project)
                state = SaveUiState.Saved(response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                state = SaveUiState.Error(gatewayMessage(this@ProcessTextSaveActivity, error))
            }
        }
    }

    private fun undo(response: SaveResponse) {
        val operationId = response.operationId ?: return
        if (state == SaveUiState.Loading) return
        state = SaveUiState.Loading
        lifecycleScope.launch {
            try {
                GatewayFactory.create(settingsRepository.read(), tokenStore.read()).undo(operationId)
                settingsRepository.clearUndo()
                state = SaveUiState.Saved(response.copy(operationId = null), getString(R.string.undo_complete))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                state = SaveUiState.Error(gatewayMessage(this@ProcessTextSaveActivity, error))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SavePrompt(text: String, onSave: (String?) -> Unit, onCancel: () -> Unit) {
    var note by androidx.compose.runtime.remember { mutableStateOf("") }
    TextCard(text)
    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(androidx.compose.ui.res.stringResource(R.string.note)) },
    )
    PrimaryAndCancel({ onSave(note) }, androidx.compose.ui.res.stringResource(R.string.save), onCancel)
}

@androidx.compose.runtime.Composable
private fun SavedScreen(state: SaveUiState.Saved, onUndo: (SaveResponse) -> Unit, onClose: () -> Unit) {
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
fun ErrorScreen(message: String, onClose: () -> Unit) {
    Text(message)
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(R.string.close)) }
}
