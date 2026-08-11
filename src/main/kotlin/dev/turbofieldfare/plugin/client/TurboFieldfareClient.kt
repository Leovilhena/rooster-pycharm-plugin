package dev.turbofieldfare.plugin.client

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Whether the local server is reachable, and what it says it is serving. */
sealed interface ServerStatus {
    /** Server answered `/health` with `ok`. [models] is what `/v1/models` listed. */
    data class Up(val models: List<String>) : ServerStatus

    /** Server did not answer, or answered with something unusable. */
    data class Down(val reason: String) : ServerStatus
}

/**
 * Talks to a TurboFieldfare server over plain HTTP on loopback.
 *
 * Uses the JDK's own [HttpClient] — the plugin adds no HTTP dependency. All calls
 * are blocking sends moved onto [Dispatchers.IO]; none of them may run on the EDT.
 */
class TurboFieldfareClient(private val baseUrl: String = DEFAULT_BASE_URL) {

    private val gson = Gson()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        // The server speaks HTTP/1.1; don't spend a round trip probing for h2c.
        .version(HttpClient.Version.HTTP_1_1)
        // The IDE installs its own default ProxySelector. We only ever talk to
        // loopback, and a proxy in that path can only break it (or leak the
        // conversation off the machine), so opt out explicitly.
        .proxy(HttpClient.Builder.NO_PROXY)
        .build()

    /**
     * Probes `/health` and then `/v1/models`. Never throws: an unreachable server
     * is an ordinary, expected state (the user simply hasn't started it), so it
     * comes back as [ServerStatus.Down] with something worth showing in the UI.
     */
    suspend fun status(): ServerStatus = withContext(Dispatchers.IO) {
        try {
            val health = get("/health")
            if (health.statusCode() != 200) {
                return@withContext ServerStatus.Down("Server returned HTTP ${health.statusCode()} for /health")
            }
            val parsed = gson.fromJson(health.body(), HealthResponse::class.java)
            if (parsed?.status != "ok") {
                return@withContext ServerStatus.Down("Server health is \"${parsed?.status ?: "unknown"}\"")
            }

            val models = get("/v1/models")
            val ids = if (models.statusCode() == 200) {
                gson.fromJson(models.body(), ModelsResponse::class.java)?.data.orEmpty().mapNotNull { it.id }
            } else {
                emptyList()
            }
            ServerStatus.Up(ids)
        } catch (e: JsonSyntaxException) {
            ServerStatus.Down("Server sent a response this plugin could not parse: ${e.message}")
        } catch (e: java.net.ConnectException) {
            ServerStatus.Down("No server listening on $baseUrl")
        } catch (e: java.net.http.HttpTimeoutException) {
            LOG.info("TurboFieldfare health check timed out against $baseUrl", e)
            ServerStatus.Down("Timed out connecting to $baseUrl")
        } catch (e: java.io.IOException) {
            LOG.info("TurboFieldfare health check failed against $baseUrl", e)
            ServerStatus.Down("${e.javaClass.simpleName}: ${e.message ?: "connection failed"}")
        }
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(TurboFieldfareClient::class.java)

        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"

        /** Loopback connects instantly or not at all; a long timeout only stalls the UI. */
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
