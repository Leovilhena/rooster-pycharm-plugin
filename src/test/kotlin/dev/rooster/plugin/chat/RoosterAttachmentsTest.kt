package dev.rooster.plugin.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoosterAttachmentsTest {

    private fun lines(path: String, start: Int?, end: Int?) =
        Attachment(path, start, end, "body")

    @Test
    fun `label names the range the way a human reads it`() {
        assertEquals("main.py:12-34", lines("main.py", 12, 34).label())
        assertEquals("main.py:12", lines("main.py", 12, 12).label())
        assertEquals("main.py", lines("main.py", null, null).label())
    }

    @Test
    fun `the prompt block is labelled and fenced`() {
        assertEquals(
            "--- main.py:1-2 ---\nbody\n--- end ---",
            lines("main.py", 1, 2).asPrompt(),
        )
    }

    /** An attachment is for the next message, not for every message after it. */
    @Test
    fun `draining sends once`() {
        val attachments = RoosterAttachments()
        attachments.add(lines("a.py", 1, 2))
        assertEquals(1, attachments.drain().size)
        assertTrue(attachments.drain().isEmpty())
        assertTrue(attachments.all().isEmpty())
    }

    @Test
    fun `removing one leaves the rest`() {
        val attachments = RoosterAttachments()
        val first = lines("a.py", 1, 2)
        attachments.add(first)
        attachments.add(lines("b.py", 3, 4))
        attachments.remove(first)
        assertEquals(listOf("b.py:3-4"), attachments.all().map { it.label() })
    }

    @Test
    fun `the composer is told when something changes`() {
        val attachments = RoosterAttachments()
        var notifications = 0
        attachments.onChange { notifications++ }
        attachments.add(lines("a.py", 1, 2))
        attachments.drain()
        assertEquals(2, notifications)
    }
}
