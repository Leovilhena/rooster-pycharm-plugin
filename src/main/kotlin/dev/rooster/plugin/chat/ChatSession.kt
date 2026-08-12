package dev.rooster.plugin.chat

import dev.rooster.plugin.client.ChatMessage

/**
 * The message history of one conversation, in the order the server will see it.
 *
 * Deliberately dumb: no summarisation, no truncation, no reordering. Whatever the
 * tool loop appends is what gets resent, so the server's cached KV prefix stays
 * valid and the transcript the user reads matches the transcript the model saw.
 */
class ChatSession {

    private val messages = mutableListOf<ChatMessage>()

    /** A snapshot safe to hand to the client while the UI keeps mutating this. */
    fun history(): List<ChatMessage> = messages.toList()

    fun add(message: ChatMessage) {
        messages += message
    }

    fun addUser(text: String) = add(ChatMessage(role = "user", content = text))

    fun addAssistant(text: String) = add(ChatMessage(role = "assistant", content = text))

    fun isEmpty(): Boolean = messages.isEmpty()

    /**
     * The most recent thing the model actually said, for the copy button.
     *
     * Read from the history rather than scraped out of the bubble, so a copy is
     * what the model said even if the rendering ever diverges from it. Assistant
     * messages carrying only `tool_calls` have no prose and are skipped — copying
     * an empty string because the last turn was a tool call is not a copy.
     */
    fun lastAssistantText(): String? =
        messages.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content

    /**
     * Rough token count: characters / 4.
     *
     * Deliberately crude. The point is to warn before the server refuses the next
     * request, and being wrong by 20% does not change that advice — while running
     * a real tokenizer would mean shipping the model's vocabulary.
     */
    fun approximateTokens(): Int = messages.sumOf { (it.content?.length ?: 0) } / 4

    fun clear() = messages.clear()
}
