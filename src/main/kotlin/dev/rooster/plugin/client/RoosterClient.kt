package dev.rooster.plugin.client

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One event from a streaming chat completion. */
sealed interface StreamEvent {
    /** A fragment of assistant text, in order. */
    data class Delta(val text: String) : StreamEvent

    /**
     * The assistant asked for tools. Emitted once, just before [Finished],
     * with the calls already reassembled from their streamed fragments.
     */
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent

    /** The server's `finish_reason` (`stop`, `length`, `tool_calls`, …). */
    data class Finished(val reason: String) : StreamEvent

    /** Something went wrong; [message] is meant to be shown to the user as-is. */
    data class Failed(val message: String) : StreamEvent
}

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
class RoosterClient(private val baseUrl: String = DEFAULT_BASE_URL) {

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
            LOG.info("Server health check timed out against $baseUrl", e)
            ServerStatus.Down("Timed out connecting to $baseUrl")
        } catch (e: java.io.IOException) {
            LOG.info("Server health check failed against $baseUrl", e)
            ServerStatus.Down("${e.javaClass.simpleName}: ${e.message ?: "connection failed"}")
        }
    }

    /**
     * Streams a chat completion as a flow of text deltas.
     *
     * The flow completes when the server sends `data: [DONE]`. Cancelling the
     * collector closes the response body, which unblocks the reader thread and
     * drops the connection — the server stops generating rather than finishing an
     * answer nobody is waiting for, which matters at ~5 tok/s.
     *
     * Errors are emitted as a [StreamEvent.Failed] rather than thrown, for the same
     * reason [status] does not throw.
     */
    fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        val body = gson.toJson(request)
        val httpRequest = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            // No overall timeout: a long generation on this hardware is normal, and
            // the user cancels by cancelling, not by us guessing a deadline.
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.io.IOException) {
            LOG.info("Chat request to $baseUrl failed", e)
            emit(StreamEvent.Failed(describe(e)))
            return@flow
        }

        if (response.statusCode() != 200) {
            emit(StreamEvent.Failed(readErrorBody(response)))
            return@flow
        }

        // This server happens to send each tool call in a single delta, but the
        // wire format allows the arguments string to arrive in fragments, so
        // reassemble by index rather than trusting that.
        val partial = PartialToolCalls()

        val stream = response.body()
        // Closing the body from the cancelling thread is what actually interrupts
        // the blocking readLine() below; checking isActive alone would not.
        val closer = currentCoroutineContext().job.invokeOnCompletion { stream.close() }

        try {
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    // SSE comments (the server sends ": ping" keepalives) and blank
                    // separators are not data.
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = try {
                        gson.fromJson(payload, ChatChunk::class.java)
                    } catch (e: JsonSyntaxException) {
                        LOG.info("Unparseable SSE payload: $payload", e)
                        continue
                    }
                    val choice = chunk?.choices?.firstOrNull() ?: continue
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Delta(it)) }
                    choice.delta?.toolCalls?.forEach { partial.accumulate(it) }
                    choice.finishReason?.let { reason ->
                        if (partial.isNotEmpty()) emit(StreamEvent.ToolCalls(partial.build()))
                        emit(StreamEvent.Finished(reason))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Expected when the collector cancelled and we closed the stream.
            currentCoroutineContext().ensureActive()
            emit(StreamEvent.Failed(describe(e)))
        } finally {
            closer.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** Reassembles streamed `tool_calls` deltas, which are keyed by `index`. */
    private class PartialToolCalls {
        private val byIndex = linkedMapOf<Int, MutableToolCall>()

        fun accumulate(delta: ToolCall) {
            val slot = byIndex.getOrPut(delta.index ?: byIndex.size) { MutableToolCall() }
            delta.id?.let { slot.id = it }
            delta.function?.name?.let { slot.name = it }
            delta.function?.arguments?.let { slot.arguments.append(it) }
        }

        fun isNotEmpty(): Boolean = byIndex.isNotEmpty()

        fun build(): List<ToolCall> = byIndex.values.map {
            ToolCall(
                id = it.id,
                function = ToolCallFunction(name = it.name, arguments = it.arguments.toString()),
            )
        }

        private class MutableToolCall {
            var id: String? = null
            var name: String? = null
            val arguments = StringBuilder()
        }
    }

    private fun readErrorBody(response: HttpResponse<java.io.InputStream>): String {
        val text = response.body().use { it.readBytes().decodeToString() }
        val parsed = try {
            gson.fromJson(text, ErrorResponse::class.java)?.error?.message
        } catch (e: JsonSyntaxException) {
            null
        }
        val detail = parsed ?: text.take(500).ifBlank { "no detail" }
        return explain(response.statusCode(), detail)
    }

    /**
     * Turns a server error into something a user can act on.
     *
     * The raw messages are short and written for an API client, not a person
     * staring at a chat panel who wants to know what to do next.
     */
    private fun explain(status: Int, detail: String): String {
        val lower = detail.lowercase()
        return when {
            lower.contains("context") || lower.contains("too long") || lower.contains("max_context") ->
                "This conversation no longer fits in the server's context window. " +
                    "Start a new chat, or restart the server with a larger --max-context. ($detail)"

            lower.contains("schema") || lower.contains("tool") && status == 400 ->
                "The server rejected the tool definitions this plugin sent: $detail"

            lower.contains("model") && status == 404 ->
                "The server does not have the model this plugin asked for. " +
                    "Clear the model id in Settings to use whatever the server is serving. ($detail)"

            else -> "Server returned HTTP $status: $detail"
        }
    }

    private fun describe(e: java.io.IOException): String = when (e) {
        is java.net.ConnectException -> "No server listening on $baseUrl"
        is java.net.http.HttpTimeoutException -> "Timed out talking to $baseUrl"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "connection failed"}"
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(RoosterClient::class.java)

        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"

        /** Loopback connects instantly or not at all; a long timeout only stalls the UI. */
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
