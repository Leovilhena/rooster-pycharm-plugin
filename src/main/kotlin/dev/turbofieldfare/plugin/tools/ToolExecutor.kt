package dev.turbofieldfare.plugin.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.project.Project
import dev.turbofieldfare.plugin.chat.ChatSession
import dev.turbofieldfare.plugin.client.ChatMessage
import dev.turbofieldfare.plugin.client.ChatRequest
import dev.turbofieldfare.plugin.client.StreamEvent
import dev.turbofieldfare.plugin.client.ToolCall
import dev.turbofieldfare.plugin.client.TurboFieldfareClient

/** What the UI needs to know while the loop runs. */
sealed interface LoopEvent {
    /** A fragment of assistant prose. */
    data class Text(val text: String) : LoopEvent

    /** A tool is about to run (or was refused); [detail] is one line for the transcript. */
    data class ToolActivity(val detail: String) : LoopEvent

    /** The loop stopped. [reason] is null on a normal finish. */
    data class Done(val reason: String?) : LoopEvent
}

/**
 * The client-side tool-calling loop.
 *
 * The server has no agentic loop of its own and no `parallel_tool_calls`: it
 * returns tool calls and stops, and the client is expected to run them and send
 * the results back. So this class is the loop, and — because it is the only place
 * a model-requested action becomes a real action — it is also the enforcement
 * point for Plan mode (see `gate`, added in Phase 5).
 *
 * One iteration is: send history + tool specs → append the assistant message
 * *unchanged* (including its `tool_calls`, so the ids line up) → run each call in
 * order → append one `role: tool` message per call, keyed by `tool_call_id` →
 * resend. History is never rewritten, which keeps the server's cached KV prefix
 * valid and keeps the transcript the user reads identical to the one the model saw.
 */
class ToolExecutor(
    private val project: Project,
    private val session: ChatSession,
    private val tools: List<Tool>,
) {

    private val gson = Gson()
    private val byName = tools.associateBy { it.name }

    /**
     * Runs the loop until the model answers without asking for tools.
     *
     * [maxIterations] is a stop, not a tuning knob: a model that keeps calling
     * tools forever would otherwise burn the whole context window and the user's
     * afternoon at 5 tokens/second.
     */
    suspend fun run(
        client: TurboFieldfareClient,
        model: String,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        emit: suspend (LoopEvent) -> Unit,
    ) {
        repeat(maxIterations) {
            val text = StringBuilder()
            var calls: List<ToolCall> = emptyList()
            var failure: String? = null

            val request = ChatRequest(
                model = model,
                messages = session.history(),
                tools = tools.map { it.toSpec() },
            )

            client.streamChat(request).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        text.append(event.text)
                        emit(LoopEvent.Text(event.text))
                    }

                    is StreamEvent.ToolCalls -> calls = event.calls
                    is StreamEvent.Finished -> Unit
                    is StreamEvent.Failed -> failure = event.message
                }
            }

            if (failure != null) {
                emit(LoopEvent.Done(failure))
                return
            }

            // Appended even when it is only tool calls with no prose: dropping it
            // would leave tool results referring to a call the model cannot see.
            session.add(
                ChatMessage(
                    role = "assistant",
                    content = text.toString().ifEmpty { null },
                    toolCalls = calls.ifEmpty { null },
                )
            )

            if (calls.isEmpty()) {
                emit(LoopEvent.Done(null))
                return
            }

            for (call in calls) {
                val result = execute(call, emit)
                session.add(ChatMessage(role = "tool", content = result, toolCallId = call.id))
            }
        }

        emit(LoopEvent.Done("Stopped after $maxIterations tool rounds without a final answer."))
    }

    private suspend fun execute(call: ToolCall, emit: suspend (LoopEvent) -> Unit): String {
        val name = call.function?.name
            ?: return "Error: the tool call had no function name.".also {
                emit(LoopEvent.ToolActivity("Ignored a malformed tool call."))
            }

        val tool = byName[name]
            ?: return "Error: no tool named \"$name\". Available tools: ${byName.keys.joinToString(", ")}."
                .also { emit(LoopEvent.ToolActivity("Refused unknown tool \"$name\".")) }

        val arguments = parseArguments(call.function.arguments)
            ?: return "Error: arguments for \"$name\" were not a JSON object."
                .also { emit(LoopEvent.ToolActivity("Refused \"$name\": unparseable arguments.")) }

        emit(LoopEvent.ToolActivity("$name(${summarise(arguments)})"))
        return try {
            tool.execute(project, arguments)
        } catch (e: Exception) {
            // A tool must not throw, but if one does, the loop still owes the model
            // a result for this call id — otherwise the next request is malformed.
            "Error: \"$name\" failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Arguments arrive as a JSON *string* the model wrote; treat it as untrusted. */
    private fun parseArguments(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return JsonObject()
        return try {
            gson.fromJson(raw, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun summarise(arguments: JsonObject): String =
        arguments.entrySet().joinToString(", ") { (key, value) ->
            "$key=${value.toString().take(60)}"
        }

    private companion object {
        const val DEFAULT_MAX_ITERATIONS = 8
    }
}
