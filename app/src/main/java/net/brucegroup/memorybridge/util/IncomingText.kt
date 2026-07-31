package net.brucegroup.memorybridge.util

import android.content.Intent
import android.content.Context
import net.brucegroup.memorybridge.R
import net.brucegroup.memorybridge.domain.TextLimits

data class ProcessTextInput(val text: String, val readOnly: Boolean)
data class SharedTextInput(val text: String, val isUrlOnly: Boolean)

object IncomingText {
    fun processText(intent: Intent): ProcessTextInput? {
        if (intent.action != Intent.ACTION_PROCESS_TEXT) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isBlank()) return null
        return ProcessTextInput(
            text = text,
            // Default to read-only if a caller omits the capability declaration.
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
        )
    }

    fun sharedText(intent: Intent): SharedTextInput? {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) return null
        return SharedTextInput(text, isStandaloneUrl(text))
    }

    fun recallResult(context: Context, contextPack: String, originalQuery: String): Intent =
        Intent().putExtra(
            Intent.EXTRA_PROCESS_TEXT,
            context.getString(R.string.context_prefix, contextPack, originalQuery),
        )

    fun isSafeInput(text: String): Boolean = TextLimits.validateInput(text)

    private fun isStandaloneUrl(text: String): Boolean =
        Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).matches(text.trim())
}
