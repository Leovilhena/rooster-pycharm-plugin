package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.nio.file.Files

/**
 * What a proposed edit would do. Read-only to build, so it can be shown even when
 * the gate refuses the edit.
 */
data class EditPreview(
    val relativePath: String,
    val oldContent: String,
    val newContent: String,
    /** True when the file does not exist yet and the edit would create it. */
    val isNewFile: Boolean,
) {
    val addedLines: Int get() = newContent.lineCount() - commonPrefixLines()
    val removedLines: Int get() = oldContent.lineCount() - commonPrefixLines()

    private fun commonPrefixLines(): Int {
        val a = oldContent.lines()
        val b = newContent.lines()
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    private fun String.lineCount(): Int = if (isEmpty()) 0 else lines().size
}

/**
 * `propose_edit` — replaces a file's entire contents.
 *
 * **Effectful, so Plan mode refuses it** (see `ToolExecutor.gate`). Even in Act
 * mode this tool does not write anything on its own; it records a proposal that
 * the user applies. The model never gets a path from intent to bytes-on-disk
 * without a human in between.
 *
 * Whole-file replacement rather than line patches: the schema stays trivial (two
 * strings), there is no patch dialect for the model to get subtly wrong, and the
 * diff is computed here from the real file rather than trusted from the model.
 * The cost is tokens, which is the right thing to spend to remove a class of
 * silent corruption.
 */
object ProposeEditTool : Tool {
    override val name = "propose_edit"
    override val description =
        "Propose replacing the entire contents of a project file. Provide the complete new file " +
            "contents, not a patch or a fragment. The user reviews the change as a diff and applies it."
    override val parameters = objectSchema(
        "path" to "File path relative to the project root.",
        "new_content" to "The complete new contents of the file.",
        required = listOf("path", "new_content"),
    )

    override val effectful = true

    override fun previewEdit(project: Project, arguments: JsonObject): EditPreview? {
        val relative = arguments.stringOrNull("path") ?: return null
        val newContent = arguments.stringOrNull("new_content") ?: return null
        val path = ProjectFiles.resolve(project, relative) ?: return null

        val exists = Files.isRegularFile(path)
        val old = if (exists) runCatching { Files.readString(path) }.getOrDefault("") else ""
        return EditPreview(
            relativePath = relative,
            oldContent = old,
            newContent = newContent,
            isNewFile = !exists,
        )
    }

    override fun execute(project: Project, arguments: JsonObject): String {
        val relative = arguments.stringOrNull("path")
            ?: return "Error: missing required argument \"path\"."
        arguments.stringOrNull("new_content")
            ?: return "Error: missing required argument \"new_content\"."
        ProjectFiles.resolve(project, relative)
            ?: return "Error: \"$relative\" is outside the open project. Nothing was changed."

        // Phase 5: the proposal is shown to the user as a diff card. Applying it is
        // a separate, human-driven step.
        return "Proposed a change to \"$relative\". It has been shown to the user as a diff and has " +
            "NOT been applied yet. Do not assume the file changed."
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
