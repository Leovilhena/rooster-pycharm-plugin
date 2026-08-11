package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The three read-only tools: `read_file`, `list_files`, `search_in_files`.
 *
 * All are `effectful = false`: they change nothing, so Plan mode lets them run.
 * They are still confined to the project by [ProjectFiles] — "read-only" limits
 * what the model can *break*, not what it can *see*, and what it can see it can
 * put in front of the user or act on later.
 *
 * Every limit here (file size, result counts) exists because the output is spent
 * from a 16k context window shared with the whole conversation; an unbounded
 * result would silently evict the user's actual question.
 */

private const val MAX_FILE_CHARS = 60_000
private const val MAX_LIST_ENTRIES = 400
private const val MAX_SEARCH_HITS = 100
private const val MAX_SEARCHED_FILES = 5_000

/** Reads the string argument [key], or null when absent/blank/not a string. */
private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

object ReadFileTool : Tool {
    override val name = "read_file"
    override val description =
        "Read a UTF-8 text file from the project. The path must be relative to the project root."
    override val parameters = objectSchema(
        "path" to "File path relative to the project root, e.g. \"src/app/main.py\".",
        required = listOf("path"),
    )

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val relative = arguments.string("path") ?: return "Error: missing required argument \"path\"."
        val path = ProjectFiles.resolve(project, relative)
            ?: return "Error: \"$relative\" is outside the open project. Only files inside the project can be read."
        if (!Files.isRegularFile(path)) return "Error: \"$relative\" is not a file."

        return try {
            val text = Files.readString(path)
            if (text.length > MAX_FILE_CHARS) {
                text.take(MAX_FILE_CHARS) +
                    "\n\n[truncated: file is ${text.length} characters, showing the first $MAX_FILE_CHARS]"
            } else {
                text
            }
        } catch (e: java.nio.charset.CharacterCodingException) {
            "Error: \"$relative\" is not UTF-8 text."
        } catch (e: java.io.IOException) {
            "Error reading \"$relative\": ${e.message}"
        }
    }
}

object ListFilesTool : Tool {
    override val name = "list_files"
    override val description =
        "List the files and directories directly inside a project directory (not recursive). " +
            "Use \".\" for the project root."
    override val parameters = objectSchema(
        "path" to "Directory path relative to the project root. Use \".\" for the root.",
        required = listOf("path"),
    )

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val relative = arguments.string("path") ?: "."
        val dir = ProjectFiles.resolve(project, relative)
            ?: return "Error: \"$relative\" is outside the open project."
        if (!Files.isDirectory(dir)) return "Error: \"$relative\" is not a directory."

        return try {
            val entries = Files.list(dir).use { stream ->
                stream.filter { it.name !in ProjectFiles.IGNORED_DIRECTORIES }
                    .limit(MAX_LIST_ENTRIES.toLong())
                    .map { if (Files.isDirectory(it)) it.name + "/" else it.name }
                    .sorted()
                    .toList()
            }
            if (entries.isEmpty()) "(empty directory)" else entries.joinToString("\n")
        } catch (e: java.io.IOException) {
            "Error listing \"$relative\": ${e.message}"
        }
    }
}

object SearchInFilesTool : Tool {
    override val name = "search_in_files"
    override val description =
        "Search the project's text files for a literal substring (case-sensitive). " +
            "Returns matching lines as \"path:line: text\"."
    override val parameters = objectSchema(
        "query" to "Literal text to search for. Not a regular expression.",
        "path" to "Optional directory to search under, relative to the project root. Defaults to the whole project.",
        required = listOf("query"),
    )

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val query = arguments.string("query") ?: return "Error: missing required argument \"query\"."
        val relative = arguments.string("path") ?: "."
        val root = ProjectFiles.resolve(project, relative)
            ?: return "Error: \"$relative\" is outside the open project."
        if (!Files.isDirectory(root)) return "Error: \"$relative\" is not a directory."

        val hits = mutableListOf<String>()
        var scanned = 0
        try {
            Files.walk(root).use { stream ->
                for (path in stream) {
                    if (hits.size >= MAX_SEARCH_HITS || scanned >= MAX_SEARCHED_FILES) break
                    if (ProjectFiles.isIgnored(path) || !Files.isRegularFile(path)) continue
                    if (!ProjectFiles.isProbablyText(path)) continue
                    scanned++
                    collectHits(project, path, query, hits)
                }
            }
        } catch (e: java.io.IOException) {
            return "Error searching \"$relative\": ${e.message}"
        }

        return when {
            hits.isEmpty() -> "No matches for \"$query\"."
            hits.size >= MAX_SEARCH_HITS -> hits.joinToString("\n") + "\n[truncated at $MAX_SEARCH_HITS matches]"
            else -> hits.joinToString("\n")
        }
    }

    private fun collectHits(project: Project, path: Path, query: String, into: MutableList<String>) {
        try {
            Files.newBufferedReader(path).use { reader ->
                var lineNumber = 0
                while (into.size < MAX_SEARCH_HITS) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (line.contains(query)) {
                        into += "${ProjectFiles.display(project, path)}:$lineNumber: ${line.trim().take(200)}"
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // A file we cannot read is not a search failure; skip it.
        } catch (e: java.nio.charset.CharacterCodingException) {
            // Binary that slipped past the heuristic.
        }
    }
}

/** Tools that are safe in any mode, because they change nothing. */
val READ_ONLY_TOOLS: List<Tool> = listOf(ReadFileTool, ListFilesTool, SearchInFilesTool)

/**
 * Every tool offered to the model. Effectful ones are offered in Plan mode too:
 * the gate refuses them at call time, which lets the model propose a change and
 * the user see the proposal, instead of the model not knowing edits exist.
 */
fun allTools(approver: ShellApprover): List<Tool> =
    READ_ONLY_TOOLS + ProposeEditTool + RunShellCommandTool(approver)
