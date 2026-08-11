package dev.rooster.plugin.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.rooster.plugin.client.ChatMessage
import dev.rooster.plugin.client.ChatRequest
import dev.rooster.plugin.client.StreamEvent
import dev.rooster.plugin.client.RoosterClient
import dev.rooster.plugin.settings.RoosterSettings
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether [extension] is a file type this local model is actually used for.
 *
 * A pure function so it's testable without an IntelliJ Platform test fixture —
 * everything else in this file needs one, this doesn't have to.
 */
internal fun isSupportedCompletionExtension(extension: String?): Boolean =
    extension?.lowercase() in setOf("py", "pyi", "sh", "bash", "zsh")

/**
 * Ghost-text completion from the local model.
 *
 * Deliberately a *separate, simpler path* from the chat tool loop: a short
 * context window, a small token budget, no tools, and no contact with the
 * Plan/Act state machine. Nothing here can edit a file or run a command — the
 * only thing it can do is offer text the user must press Tab to accept — so it
 * needs none of the gating the chat side has, and giving it any would be an
 * invitation to grow one.
 *
 * **Off by default.** At ~5 tok/s (and a documented ~54s cold start) as-you-type
 * completion arrives after the user has typed past it; the setting exists so
 * that turning it on is a choice made with that knowledge.
 *
 * Cancellation is the platform's: a new keystroke cancels the coroutine, which
 * cancels the flow collection, which closes the HTTP response and stops the
 * server generating. Nothing is queued behind a stale request.
 */
class RoosterInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("TurboFieldfare")

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        RoosterSettings.getInstance().state.completionDebounceMs.milliseconds

    /**
     * The opt-in check.
     *
     * With `completionAutomatic` off, only an explicit invocation
     * ([InlineCompletionEvent.DirectCall]) is honoured — typing produces nothing.
     */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = RoosterSettings.getInstance().state
        if (!settings.completionEnabled) return false
        return settings.completionAutomatic || event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = RoosterSettings.getInstance()
        val offset = request.endOffset

        // Document access needs a read action; the text is copied out immediately so
        // nothing else here runs under the lock.
        val (prefix, suffix, supported) = readAction {
            val extension = FileDocumentManager.getInstance().getFile(request.document)?.extension?.lowercase()
            val text = request.document.immutableCharSequence
            val start = (offset - PREFIX_CHARS).coerceAtLeast(0)
            val end = (offset + SUFFIX_CHARS).coerceAtMost(text.length)
            Triple(
                text.subSequence(start, offset).toString(),
                text.subSequence(offset, end).toString(),
                isSupportedCompletionExtension(extension),
            )
        }
        if (!supported) return InlineCompletionSuggestion.Empty

        if (prefix.isBlank()) return InlineCompletionSuggestion.Empty

        val completion = requestCompletion(prefix, suffix, settings)
            ?: return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completion))
        }
    }

    private suspend fun requestCompletion(
        prefix: String,
        suffix: String,
        settings: RoosterSettings,
    ): String? {
        val client = RoosterClient(settings.baseUrl())
        val request = ChatRequest(
            model = settings.state.modelId.ifBlank { FALLBACK_MODEL },
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = buildPrompt(prefix, suffix)),
            ),
            maxTokens = settings.state.completionMaxTokens,
            temperature = 0.2,
        )

        val text = StringBuilder()
        var failed = false
        client.streamChat(request).collect { event ->
            when (event) {
                is StreamEvent.Delta -> text.append(event.text)
                is StreamEvent.Failed -> failed = true
                else -> Unit
            }
        }
        if (failed) return null

        return text.toString().stripCodeFence().takeIf { it.isNotBlank() }
    }

    private fun buildPrompt(prefix: String, suffix: String): String = buildString {
        append("Continue the code at <CURSOR>. Reply with the continuation only.\n\n")
        append(prefix)
        append("<CURSOR>")
        append(suffix)
    }

    /** Small models wrap answers in fences even when told not to. */
    private fun String.stripCodeFence(): String {
        val trimmed = trimEnd()
        if (!trimmed.trimStart().startsWith("```")) return trimmed
        return trimmed.substringAfter('\n', "").substringBeforeLast("```").trimEnd()
    }

    private companion object {
        // Cut from 1500/300: fewer prompt tokens means less prefill before decode
        // even starts, which is most of what's addressable on a ~5 tok/s decoder.
        const val PREFIX_CHARS = 600
        const val SUFFIX_CHARS = 150

        const val FALLBACK_MODEL = "gemma-4-26b-a4b-it"
        const val SYSTEM_PROMPT =
            "You complete code. Output only the text that should be inserted at the cursor. " +
                "No explanation, no markdown fences, no repetition of the code before the cursor."
    }
}
