package dev.rooster.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.nio.file.Files

/**
 * `write_memory` — records a fact that should outlive this chat session.
 *
 * **Effectful, so Plan mode refuses it**, through the existing
 * `ToolExecutor.gate` and no new gating logic of its own. Even in Act mode this
 * tool writes nothing: like `propose_edit`, it produces a preview that appears as
 * a card, and a human clicking Apply is what puts bytes on disk. Every write —
 * project or global — goes through that click. Memory is exactly the kind of
 * thing that must not accumulate silently: a fact the model decided to remember,
 * that the user never saw, would shape every future session invisibly.
 *
 * It reuses the **edit** approval pattern rather than the shell one: a memory
 * write is a file write with the same risk shape as `propose_edit`, not a
 * one-shot irreversible external action, so there is no reason to suspend the
 * model's turn waiting for a human the way `run_shell_command` does.
 *
 * Whole-file replacement, same reasoning as `propose_edit`: no patch dialect for
 * a small local model to get subtly wrong.
 *
 * The `# Title` first line is written **here**, from the separate `title`
 * argument, never taken from the body. The index reads that line, so leaving it
 * to model discipline would mean an index entry that silently disagrees with the
 * file whenever the model forgot the convention.
 */
object WriteMemoryTool : Tool {
    override val name = "write_memory"
    override val description =
        "Record a durable fact in memory so it is available in future chat sessions — a project " +
            "convention, or a user preference. The user reviews the proposed file and applies it; " +
            "nothing is saved until they do. Replaces the whole topic, so include everything the " +
            "topic should still say, not just the new part."
    override val parameters = objectSchema(
        "scope" to "\"project\" for a fact about this project only, or \"global\" for a preference that applies to every project.",
        "topic" to "Short topic name, lowercase words joined by hyphens, e.g. \"testing-conventions\". No path, no \".md\".",
        "title" to "One short line summarising the topic. Shown in the memory index of every future session.",
        "content" to "The full body of the topic, in plain markdown. Do not include the title heading; it is added automatically.",
        required = listOf("scope", "topic", "title", "content"),
    )

    override val effectful = true

    override fun previewEdit(project: Project, arguments: JsonObject): EditPreview? {
        val scope = arguments.memoryString("scope") ?: return null
        val topic = arguments.memoryString("topic") ?: return null
        val title = arguments.memoryString("title") ?: return null
        val content = arguments.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: return null

        val path = resolveMemoryFile(project, scope, topic) ?: return null
        val exists = Files.isRegularFile(path)
        val old = if (exists) runCatching { Files.readString(path) }.getOrDefault("") else ""

        return EditPreview(
            // For global scope this is the bare slug, because that is the only thing
            // `GlobalMemory.resolve()` accepts — and re-deriving the path from the
            // slug is what lets the applier re-check it instead of trusting a path.
            relativePath = if (scope == "global") topic else "$PROJECT_MEMORY_DIR/$topic.md",
            oldContent = old,
            newContent = body(title, content),
            isNewFile = !exists,
            scope = if (scope == "global") PathScope.GlobalMemory else PathScope.Project,
        )
    }

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val scope = arguments.memoryString("scope")
            ?: return "Error: missing required argument \"scope\"."
        val topic = arguments.memoryString("topic")
            ?: return "Error: missing required argument \"topic\"."
        arguments.memoryString("title")
            ?: return "Error: missing required argument \"title\"."
        arguments.get("content")?.takeIf { it.isJsonPrimitive }
            ?: return "Error: missing required argument \"content\"."

        resolveMemoryFile(project, scope, topic) ?: return memoryArgumentError(scope, topic)

        return "Proposed saving the \"$topic\" topic to $scope memory. It has been shown to the user " +
            "for approval and has NOT been saved yet. Do not assume it was remembered."
    }

    /** The on-disk format: a deterministic title line, then the model's body. */
    internal fun body(title: String, content: String): String {
        val trimmed = content.trim()
        return "# $title\n\n$trimmed\n"
    }
}
