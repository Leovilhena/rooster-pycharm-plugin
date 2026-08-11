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
