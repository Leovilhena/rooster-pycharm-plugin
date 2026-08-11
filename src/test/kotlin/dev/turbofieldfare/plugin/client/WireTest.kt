package dev.turbofieldfare.plugin.client

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fixtures below are verbatim responses captured with curl from a running
 * TurboFieldfare server (`gemma-4-26b-a4b-it`, 2026-08-11). If the server changes
 * its wire format, this test is where it should break first.
 */
class WireTest {

    private val gson = Gson()

    @Test
    fun `parses health response`() {
        val parsed = gson.fromJson("""{"status":"ok"}""", HealthResponse::class.java)
        assertEquals("ok", parsed.status)
    }

    @Test
    fun `parses models response`() {
        val json = """
            {"object":"list","data":[{"object":"model","created":0,
            "id":"gemma-4-26b-a4b-it","owned_by":"turbofieldfare"}]}
        """.trimIndent()

        val parsed = gson.fromJson(json, ModelsResponse::class.java)

        assertEquals(listOf("gemma-4-26b-a4b-it"), parsed.data?.map { it.id })
        assertEquals("turbofieldfare", parsed.data?.first()?.ownedBy)
    }

    @Test
    fun `tolerates a models response with no data array`() {
        val parsed = gson.fromJson("""{"object":"list"}""", ModelsResponse::class.java)
        assertEquals(null, parsed.data)
    }
}
