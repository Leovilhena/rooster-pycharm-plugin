package dev.turbofieldfare.plugin.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySlugTest {

    @Test
    fun `accepts lowercase hyphenated slugs`() {
        assertTrue(isValidSlug("testing-conventions"))
        assertTrue(isValidSlug("prefers-early-returns"))
        assertTrue(isValidSlug("python"))
        assertTrue(isValidSlug("pep8"))
        assertTrue(isValidSlug("a1-b2-c3"))
    }

    @Test
    fun `rejects anything that could traverse a directory`() {
        assertFalse(isValidSlug(".."))
        assertFalse(isValidSlug("../secrets"))
        assertFalse(isValidSlug("a/b"))
        assertFalse(isValidSlug("/etc/passwd"))
        assertFalse(isValidSlug("a.b"))
        assertFalse(isValidSlug("."))
        assertFalse(isValidSlug("~"))
    }

    @Test
    fun `rejects malformed slugs`() {
        assertFalse(isValidSlug(""))
        assertFalse(isValidSlug("Uppercase"))
        assertFalse(isValidSlug("with space"))
        assertFalse(isValidSlug("-leading"))
        assertFalse(isValidSlug("trailing-"))
        assertFalse(isValidSlug("double--hyphen"))
        assertFalse(isValidSlug("under_score"))
    }

    @Test
    fun `rejects over-length slugs`() {
        assertTrue(isValidSlug("a".repeat(MAX_SLUG_LENGTH)))
        assertFalse(isValidSlug("a".repeat(MAX_SLUG_LENGTH + 1)))
    }
}
