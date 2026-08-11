package dev.rooster.plugin.client

import com.google.gson.annotations.SerializedName

/**
 * Wire format for the TurboFieldfare server's OpenAI-compatible API.
 *
 * Field names match the JSON exactly, so Gson needs no custom naming policy.
 * Nullable fields are the ones the server genuinely omits — Gson leaves absent
 * fields null regardless of Kotlin's non-null types, so a field that is not
 * nullable here is a promise this code has actually verified against the real
 * server, not a wish.
 */

// --- GET /health -------------------------------------------------------------

data class HealthResponse(val status: String?)

// --- GET /v1/models ----------------------------------------------------------

data class ModelsResponse(val data: List<ModelInfo>?)

data class ModelInfo(
    val id: String?,
    @SerializedName("owned_by") val ownedBy: String?,
)

// --- POST /v1/chat/completions ----------------------------------------------

data class ChatMessage(
    val role: String,
    val content: String? = null,
    /** Present only on assistant messages that asked for tools. */
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** Present only on `role: tool` messages; ties the result to the call. */
    @SerializedName("tool_call_id") val toolCallId: String? = null,
)

data class ToolCall(
    val id: String?,
    val type: String? = "function",
    val function: ToolCallFunction?,
    /** Streaming only: which call this delta belongs to. */
    val index: Int? = null,
)

data class ToolCallFunction(
    val name: String?,
    /** A JSON *string*, not an object — that is what the wire format says. */
    val arguments: String?,
)

/** A tool offered to the model. [parameters] is a JSON Schema object. */
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: com.google.gson.JsonObject,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val tools: List<ToolSpec>? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
)

data class ChatChunk(val choices: List<ChunkChoice>?)

data class ChunkChoice(
    val delta: ChunkDelta?,
    @SerializedName("finish_reason") val finishReason: String?,
)

data class ChunkDelta(
    val role: String?,
    val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

/** Error envelope the server returns on a 4xx/5xx. */
data class ErrorResponse(val error: ErrorDetail?)

data class ErrorDetail(val message: String?, val type: String?)
