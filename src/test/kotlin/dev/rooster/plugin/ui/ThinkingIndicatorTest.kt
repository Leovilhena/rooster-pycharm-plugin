package dev.rooster.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingIndicatorTest {

    @Test
    fun `seconds below a minute`() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("59s", formatElapsed(59))
    }

    @Test
    fun `a minute and over`() {
        assertEquals("1m 0s", formatElapsed(60))
        assertEquals("1m 30s", formatElapsed(90))
        assertEquals("10m 5s", formatElapsed(605))
    }

    @Test
    fun `a phrase holds for five seconds, then changes`() {
        val first = phraseFor(0)
        assertEquals(first, phraseFor(4))
        assertTrue(phraseFor(5) != first)
    }

    @Test
    fun `phrases cycle rather than running out`() {
        // Five phrases at five seconds each: second 25 is back to the first.
        assertEquals(phraseFor(0), phraseFor(25))
        // And a generation running for an hour still has something to say.
        assertTrue(phraseFor(3600).isNotEmpty())
    }
}
