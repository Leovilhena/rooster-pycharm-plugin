package dev.rooster.plugin.memory

import dev.rooster.plugin.chat.ChatSession
import dev.rooster.plugin.client.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The memory index costs context, and the existing warning has to know it.
 *
 * The panel warns once a conversation passes 75% of the configured window, using
 * `ChatSession.approximateTokens()`. Memory needs no new budget logic *provided*
 * the index is an ordinary message in that session — if it were carried
 * somewhere else, or injected at request time, it would be spent but invisible,
 * and the warning would fire later than it should on exactly the conversations
 * where memory made things tight.
 */
private const val WINDOW = 16_384

class MemoryContextBudgetTest {

    @Test
    fun `the index counts toward the token estimate like any other message`() {
        val index = assertNotNull(
            MemoryIndex.format(
                listOf(MemoryTopic("testing-conventions", "We use pytest, not unittest")),
                listOf(MemoryTopic("prefers-early-returns", "Prefer early returns over nested if/else")),
            )
        )
        val session = ChatSession()

        val empty = session.approximateTokens()
        session.add(ChatMessage(role = "system", content = index))

        assertEquals(0, empty)
        assertEquals(index.length / 4, session.approximateTokens())
    }

    @Test
    fun `even a maxed-out index is a small fraction of the window`() {
        // 200 topics is far past what a curated corpus should reach; the cap is what
        // keeps a hand-filled directory from evicting the user's actual question.
        val topics = (1..200).map { MemoryTopic("topic-$it", "A reasonably wordy title for topic $it") }
        val index = assertNotNull(MemoryIndex.format(topics, emptyList()))

        val tokens = index.length / 4

        assertTrue(tokens < WINDOW / 20, "index cost $tokens tokens, over 5% of a $WINDOW window")
    }

    @Test
    fun `the warning threshold is unaffected by where the tokens came from`() {
        // The 75% crossing is a property of the total, so an index-carrying session
        // reaches it exactly as many characters earlier as the index is long.
        val index = "## Project memory (this project only)\n- a-topic: A title\n"
        val withMemory = ChatSession().apply { add(ChatMessage(role = "system", content = index)) }
        val without = ChatSession()

        val conversation = "x".repeat(40_000)
        withMemory.addUser(conversation)
        without.addUser(conversation)

        assertEquals(index.length / 4, withMemory.approximateTokens() - without.approximateTokens())
    }
}
