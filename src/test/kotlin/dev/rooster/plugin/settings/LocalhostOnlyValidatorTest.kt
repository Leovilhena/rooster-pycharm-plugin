package dev.rooster.plugin.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalhostOnlyValidatorTest {

    @Test
    fun `accepts loopback forms`() {
        listOf(
            "127.0.0.1",
            "127.1.2.3",
            "localhost",
            "LOCALHOST",
            "  127.0.0.1  ",
            "::1",
            "[::1]",
            "0:0:0:0:0:0:0:1",
        ).forEach { assertTrue(LocalhostOnlyValidator.isLocalhost(it), "should accept $it") }
    }

    @Test
    fun `rejects everything else`() {
        listOf(
            "",
            "0.0.0.0",
            "192.168.1.10",
            "10.0.0.1",
            "example.com",
            // Resolves to loopback on many machines, but is still a name: what a
            // name resolves to can change under us.
            "localhost.evil.example.com",
            // Octet out of range — the regex alone would let this through.
            "127.999.1.1",
            // Looks loopback-ish, is not.
            "1270.0.0.1",
            "127.0.0.1.evil.com",
        ).forEach { assertFalse(LocalhostOnlyValidator.isLocalhost(it), "should reject $it") }
    }

    @Test
    fun `reject explains itself only for bad hosts`() {
        assertNull(LocalhostOnlyValidator.reject("127.0.0.1"))
        assertNotNull(LocalhostOnlyValidator.reject("example.com"))
    }
}
