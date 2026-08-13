package net.brucegroup.memorybridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brucegroup.memorybridge.R
import net.brucegroup.memorybridge.domain.GatewayException

@Composable
fun BridgePage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        },
    )
}

@Composable
fun BusyIndicator() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(androidx.compose.ui.res.stringResource(R.string.processing))
    }
}

@Composable
fun TextCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PrimaryAndCancel(onPrimary: () -> Unit, primaryText: String, onCancel: () -> Unit) {
    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryText) }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(androidx.compose.ui.res.stringResource(R.string.cancel))
    }
}

fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Memory Bridge context", value))
}

fun copyAndFinish(context: Context, value: String, finish: () -> Unit) {
    copyToClipboard(context, value)
    finish()
}

fun gatewayMessage(context: Context, error: Throwable): String {
    val message = when (error) {
        is GatewayException.Configuration -> context.getString(R.string.error_configuration)
        is GatewayException.Offline -> context.getString(R.string.error_offline)
        is GatewayException.Timeout -> context.getString(R.string.error_timeout)
        is GatewayException.ResponseTooLarge -> context.getString(R.string.error_response_too_large)
        is GatewayException.Protocol -> context.getString(R.string.error_protocol)
        is GatewayException.Http -> when (error.statusCode) {
        401 -> context.getString(R.string.error_unauthorized)
        403 -> context.getString(R.string.error_forbidden)
        404 -> context.getString(R.string.error_not_found)
        429 -> context.getString(R.string.error_rate_limited)
        in 500..599 -> context.getString(R.string.error_server)
            else -> context.getString(R.string.request_failed)
        }
        else -> context.getString(R.string.request_failed)
    }
    val requestId = (error as? GatewayException)?.requestId
    return if (requestId == null) message else context.getString(R.string.error_with_request_id, message, requestId)
}
