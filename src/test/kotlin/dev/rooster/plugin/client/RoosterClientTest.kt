package dev.rooster.plugin.client

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class RoosterClientTest {

    /**
     * The common case in practice is "user hasn't started the server yet". That
     * must surface as a value, never as an exception escaping into the UI thread.
     */
    @Test
    fun `unreachable server reports Down instead of throwing`() = runBlocking {
        // Port 1 on loopback: nothing can be listening there.
        val status = RoosterClient("http://127.0.0.1:1").status()
        assertTrue(status is ServerStatus.Down, "expected Down, got $status")
        assertTrue(status.reason.isNotBlank())
    }
}
