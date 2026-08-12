package dev.rooster.plugin.chat

import dev.rooster.plugin.client.ChatMessage
import dev.rooster.plugin.client.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatSessionTest {

    @Test
    fun `no assistant turn yet`() {
        val session = ChatSession()
        session.addUser("hello")
        assertNull(session.lastAssistantText())
    }

    @Test
    fun `the most recent assistant turn wins`() {
        val session = ChatSession()
        session.addAssistant("first")
        session.addUser("and?")
        session.addAssistant("second")
        assertEquals("second", session.lastAssistantText())
    }

    /**
     * The case the copy button would otherwise get wrong: the last assistant
     * message in a tool-using turn carries `tool_calls` and no prose, so copying
     * it would put nothing on the clipboard.
     */
    @Test
    fun `a tool-call-only turn is not the last thing said`() {
        val session = ChatSession()
        session.addAssistant("the real answer")
        session.add(
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall(id = "1", function = null)),
            )
        )
        assertEquals("the real answer", session.lastAssistantText())
    }
}
