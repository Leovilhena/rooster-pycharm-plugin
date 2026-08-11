package dev.rooster.plugin.memory

import java.nio.file.Files
import java.nio.file.Path

/** One memory file, reduced to what the index shows: its slug and its title line. */
data class MemoryTopic(val slug: String, val title: String)

/**
 * Builds the small always-loaded memory index.
 *
 * There is no persisted index file: the index is computed by scanning the two
 * directories at panel construction. Earned by corpus size — this is one
 * assistant's curated facts, not an accumulating knowledge base, so a few dozen
 * one-line reads are cheap, and there is no manifest that can drift out of sync
 * with the files it claims to describe.
 *
 * Everything here is pure or takes a root [Path], so it is unit-testable without
 * an IDE — the same trick `ProjectFiles.resolve`'s root-relative overload uses.
 */
object MemoryIndex {

    /**
     * Character budget for the whole index message.
     *
     * ~2000 chars is ~500 tokens on `ChatSession.approximateTokens()`'s chars/4
     * heuristic: about 3% of a 16K window, paid once per session. A directory
     * somebody hand-fills with a hundred files must not silently eat the context
     * the user's actual question needs, so the list is cut and says it was cut.
     */
    const val MAX_INDEX_CHARS = 2_000

    /** A pathological one-line file must not become a pathological index entry. */
    private const val MAX_TITLE_CHARS = 120

    private const val FOOTER =
        "Call read_memory_file(scope, topic) for the full content of a topic. Call " +
            "write_memory(scope, topic, title, content) to record a new fact — it is shown " +
            "to the user for approval before anything is saved."

    /**
     * The index message, or null when there is nothing to say.
     *
     * Null rather than a "(nothing yet)" placeholder: on a fresh install that
     * placeholder would burn tokens every session to state the obvious, and give
     * the model a memory system to talk about before one exists.
     */
    fun message(projectDir: Path?, globalDir: Path?): String? =
        format(topics(projectDir), topics(globalDir))

    /** Pure formatting half, split out so the text is testable without a filesystem. */
    fun format(projectTopics: List<MemoryTopic>, globalTopics: List<MemoryTopic>): String? {
        if (projectTopics.isEmpty() && globalTopics.isEmpty()) return null

        val text = StringBuilder()
        var omitted = 0

        fun section(heading: String, topics: List<MemoryTopic>) {
            if (topics.isEmpty()) return
            val lines = mutableListOf<String>()
            for (topic in topics) {
                val line = "- ${topic.slug}: ${topic.title}"
                // Budget checked against what is already committed plus this line,
                // so the cut happens before the overflow, not after it.
                if (text.length + lines.sumOf { it.length + 1 } + line.length > MAX_INDEX_CHARS) {
                    omitted++
                } else {
                    lines += line
                }
            }
            if (lines.isEmpty()) return
            text.append(heading).append('\n')
            text.append(lines.joinToString("\n")).append('\n')
        }

        section("## Project memory (this project only)", projectTopics)
        section("## Global memory (applies to every project)", globalTopics)

        if (omitted > 0) text.append("[$omitted more topics not shown]\n")
        text.append('\n').append(FOOTER)
        return text.toString()
    }

    /** Every valid memory topic in [dir], sorted by slug. Missing dir means none. */
    fun topics(dir: Path?): List<MemoryTopic> {
        if (dir == null || !Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.toList()
                    .mapNotNull { path ->
                        val name = path.fileName.toString()
                        if (!name.endsWith(".md") || !Files.isRegularFile(path)) return@mapNotNull null
                        val slug = name.removeSuffix(".md")
                        // A hand-dropped file whose name is not a slug is skipped rather
                        // than indexed: the tools could never fetch it back by name.
                        if (!isValidSlug(slug)) return@mapNotNull null
                        MemoryTopic(slug, titleOf(slug, firstLineOf(path)))
                    }
                    .sortedBy { it.slug }
            }
        } catch (e: java.io.IOException) {
            emptyList()
        }
    }

    /**
     * The `# Title` line, or the slug when there isn't one.
     *
     * The fallback is what keeps hand-authored files first-class: a file somebody
     * wrote in their editor without the plugin's header still appears in the index
     * and is still fetchable. Nothing about the format requires the plugin to have
     * written it.
     */
    fun titleOf(slug: String, firstLine: String?): String {
        val heading = firstLine?.trim()?.takeIf { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        return (heading?.takeIf { it.isNotEmpty() } ?: slug).take(MAX_TITLE_CHARS)
    }

    private fun firstLineOf(path: Path): String? =
        try {
            Files.newBufferedReader(path).use { it.readLine() }
        } catch (e: java.io.IOException) {
            null
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
}
