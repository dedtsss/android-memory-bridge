package net.brucegroup.memorybridge.ui

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.tooling.preview.Preview
import net.brucegroup.memorybridge.R

@Preview(showBackground = true, locale = "ru")
@Composable
private fun SettingsPreview() {
    MemoryBridgeTheme {
        BridgePage(androidx.compose.ui.res.stringResource(R.string.settings_title)) {
            OutlinedTextField(
                value = "https://gateway.example",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text(androidx.compose.ui.res.stringResource(R.string.gateway_url)) },
            )
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.test_connection))
            }
        }
    }
}

@Preview(showBackground = true, locale = "ru")
@Composable
private fun RecallResultPreview() {
    MemoryBridgeTheme {
        BridgePage(androidx.compose.ui.res.stringResource(R.string.recall_from_memory)) {
            TextCard("Короткий context-pack из внешней памяти.")
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.copy_and_open_chatgpt))
            }
        }
    }
}
