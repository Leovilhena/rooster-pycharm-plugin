package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.turbofieldfare.plugin.client.FunctionSpec
import dev.turbofieldfare.plugin.client.ToolSpec

/**
 * One capability offered to the model.
 *
 * Everything the model can cause to happen in the user's IDE arrives through an
 * implementation of this interface, so this is the narrow waist where gating and
 * confinement are applied. Two rules hold for every implementation:
 *
 * - **Arguments are untrusted.** They are whatever a local 4B-class model emitted
 *   into a JSON string; they may be missing, mistyped, or adversarial (a prompt
 *   injected via a file the model read is enough). Every implementation validates
 *   its own arguments and returns an error string rather than throwing.
 * - **[effectful] is a safety declaration, not a hint.** `ToolExecutor` refuses to
 *   run an effectful tool in Plan mode. A tool that changes anything on disk, or
 *   outside the IDE, must declare `true` — the gate believes this flag, and the
 *   model never sees or sets it.
 */
interface Tool {
    val name: String

    /** Shown to the model. Worth being specific: it is the only spec the model gets. */
    val description: String

    /**
     * JSON Schema for the arguments.
     *
     * Kept to plain types (`object`, `string`, `array`) with no `oneOf`/`allOf`
     * and no `additionalProperties`, because the server rejects schemas that use
     * them.
     */
    val parameters: JsonObject

    /** True if running this tool changes something. Blocked outright in Plan mode. */
    val effectful: Boolean get() = false

    /**
     * Runs the tool and returns the text handed back to the model as the
     * `role: tool` result. Must not throw: an error is a result too, and a
     * thrown exception would abandon a tool call the model is waiting on.
     */
    fun execute(project: Project, arguments: JsonObject): String

    fun toSpec(): ToolSpec = ToolSpec(
        function = FunctionSpec(name = name, description = description, parameters = parameters),
    )
}

/** Builds `{"type":"object","properties":{...},"required":[...]}`. */
fun objectSchema(vararg properties: Pair<String, String>, required: List<String>): JsonObject {
    val props = JsonObject()
    properties.forEach { (name, description) ->
        props.add(name, JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
        })
    }
    return JsonObject().apply {
        addProperty("type", "object")
        add("properties", props)
        add("required", com.google.gson.JsonArray().apply { required.forEach { add(it) } })
    }
}
