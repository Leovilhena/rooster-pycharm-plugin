package dev.turbofieldfare.plugin.client

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
    val content: String?,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
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
)

/** Error envelope the server returns on a 4xx/5xx. */
data class ErrorResponse(val error: ErrorDetail?)

data class ErrorDetail(val message: String?, val type: String?)
