package net.brucegroup.memorybridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.brucegroup.memorybridge.ui.BridgePage
import net.brucegroup.memorybridge.ui.MemoryBridgeTheme

/** Internal harness for testing Process Text and Share contracts without ChatGPT. */
class DebugHarnessActivity : ComponentActivity() {
    private var editableText by mutableStateOf("Example text for Memory Bridge")
    private var readOnlyText by mutableStateOf("Read-only example question")
    private val processText = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { editableText = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MemoryBridgeTheme {
                BridgePage(getString(R.string.debug_harness)) {
                    OutlinedTextField(
                        value = editableText,
                        onValueChange = { editableText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(getString(R.string.editable_field)) },
                    )
                    Button(onClick = { processText.launch(processIntent(editableText, false)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.send_process_text))
                    }
                    OutlinedTextField(
                        value = readOnlyText,
                        onValueChange = { readOnlyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(getString(R.string.readonly_text)) },
                        readOnly = true,
                    )
                    Button(onClick = { processText.launch(processIntent(readOnlyText, true)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.send_process_text))
                    }
                    Button(onClick = {
                        startActivity(Intent(this@DebugHarnessActivity, ShareActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, editableText)
                        })
                    }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.send_share)) }
                }
            }
        }
    }

    private fun processIntent(text: String, readOnly: Boolean): Intent =
        Intent(this, ProcessTextRecallActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
        }
}
