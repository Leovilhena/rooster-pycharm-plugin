package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.turbofieldfare.plugin.memory.GlobalMemory
import dev.turbofieldfare.plugin.memory.isValidSlug
import java.nio.file.Files
import java.nio.file.Path

/** Where project-scoped memory lives, relative to the project root. */
internal const val PROJECT_MEMORY_DIR = ".turbofieldfare/memory"

/**
 * A memory topic is short by construction; a long one should have been split.
 *
 * Lower than `read_file`'s 60k for that reason: this content is fetched *into* a
 * conversation that already carries the index and the user's question, and a
 * runaway memory file would evict them.
 */
private const val MAX_MEMORY_CHARS = 8_000

/**
 * Resolves a `(scope, topic)` pair to a file on disk, or null if either is invalid.
 *
 * Shared by `read_memory_file` and `write_memory` so the two cannot disagree about
 * where a topic lives — a read that resolved differently from the matching write
 * would be a silently unfetchable memory.
 *
 * Both scopes end at a trust boundary, but not the same one: `project` is an
 * ordinary project-relative path through `ProjectFiles.resolve()`, symlinks and
 * all; `global` has no project to be confined to, so the validated slug *is* the
 * confinement (see `memory/GlobalMemory.kt`).
 */
internal fun resolveMemoryFile(project: Project, scope: String, topic: String): Path? {
    if (!isValidSlug(topic)) return null
    return when (scope) {
        "project" -> ProjectFiles.resolve(project, "$PROJECT_MEMORY_DIR/$topic.md")
        "global" -> GlobalMemory.resolve(topic)
        else -> null
    }
}

/** Explains a null from [resolveMemoryFile] to the model, in its own terms. */
internal fun memoryArgumentError(scope: String, topic: String): String = when {
    scope != "project" && scope != "global" ->
        "Error: \"scope\" must be either \"project\" or \"global\", not \"$scope\"."

    !isValidSlug(topic) ->
        "Error: \"$topic\" is not a valid topic name. Topic names are lowercase words joined by " +
            "hyphens, e.g. \"testing-conventions\". Do not include a directory or a file extension."

    else -> "Error: \"$topic\" could not be resolved in $scope memory."
}

/**
 * `read_memory_file` — fetches one memory topic in full.
 *
 * `effectful = false`, so it runs in Plan mode as well as Act: reading a fact the
 * user themselves recorded changes nothing, and it is the same class of action as
 * `read_file`.
 *
 * The other half of the memory design: the session automatically carries only a
 * short index of topic names and titles, and the model pulls the full text of the
 * one topic it actually needs. That is what keeps a growing memory corpus from
 * costing the whole conversation a fixed tax on every turn.
 */
object ReadMemoryFileTool : Tool {
    override val name = "read_memory_file"
    override val description =
        "Read the full contents of one memory topic listed in the memory index at the start of " +
            "this conversation. Use this when a listed topic looks relevant to the question."
    override val parameters = objectSchema(
        "scope" to "\"project\" for memory about this project only, or \"global\" for memory that applies to every project.",
        "topic" to "The topic name exactly as it appears in the memory index, e.g. \"testing-conventions\". No path, no \".md\".",
        required = listOf("scope", "topic"),
    )

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val scope = arguments.memoryString("scope") ?: return "Error: missing required argument \"scope\"."
        val topic = arguments.memoryString("topic") ?: return "Error: missing required argument \"topic\"."

        val path = resolveMemoryFile(project, scope, topic) ?: return memoryArgumentError(scope, topic)
        if (!Files.isRegularFile(path)) {
            return "Error: there is no \"$topic\" topic in $scope memory. Only the topics listed in " +
                "the memory index exist; do not guess topic names."
        }

        return try {
            val text = Files.readString(path)
            if (text.length > MAX_MEMORY_CHARS) {
                text.take(MAX_MEMORY_CHARS) +
                    "\n\n[truncated: this memory topic is ${text.length} characters, showing the first " +
                    "$MAX_MEMORY_CHARS. It should probably be split into smaller topics.]"
            } else {
                text
            }
        } catch (e: java.nio.charset.CharacterCodingException) {
            "Error: the \"$topic\" memory file is not UTF-8 text."
        } catch (e: java.io.IOException) {
            "Error reading the \"$topic\" memory file: ${e.message}"
        }
    }
}

internal fun JsonObject.memoryString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
