package dev.turbofieldfare.plugin.memory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryIndexTest {

    private val projectDir: Path = Files.createTempDirectory("tff-mem-project")
    private val globalDir: Path = Files.createTempDirectory("tff-mem-global")

    private fun write(dir: Path, name: String, content: String) =
        dir.resolve(name).also { it.writeText(content) }

    @Test
    fun `two empty directories produce no message at all`() {
        assertNull(MemoryIndex.message(projectDir, globalDir))
        assertNull(MemoryIndex.message(null, null))
        assertNull(MemoryIndex.message(projectDir.resolve("does-not-exist"), null))
    }

    @Test
    fun `lists both scopes under their own headings`() {
        write(projectDir, "testing-conventions.md", "# We use pytest, not unittest\n\nDetails.\n")
        write(globalDir, "prefers-early-returns.md", "# Prefer early returns over nested if/else\n")

        val message = assertNotNull(MemoryIndex.message(projectDir, globalDir))

        assertTrue(message.contains("## Project memory (this project only)"))
        assertTrue(message.contains("- testing-conventions: We use pytest, not unittest"))
        assertTrue(message.contains("## Global memory (applies to every project)"))
        assertTrue(message.contains("- prefers-early-returns: Prefer early returns over nested if/else"))
        assertTrue(message.contains("read_memory_file"))
        assertTrue(message.contains("write_memory"))
    }

    @Test
    fun `a scope with no files gets no heading`() {
        write(projectDir, "only-project.md", "# Only project\n")

        val message = assertNotNull(MemoryIndex.message(projectDir, globalDir))

        assertTrue(message.contains("## Project memory (this project only)"))
        assertTrue(!message.contains("## Global memory"))
    }

    @Test
    fun `a file with no heading line falls back to its filename`() {
        // Hand-authored outside the plugin: still indexed, still fetchable.
        write(projectDir, "hand-written.md", "just some prose, no heading\n")
        write(projectDir, "empty-file.md", "")
        write(projectDir, "bare-hash.md", "#\n")

        val topics = MemoryIndex.topics(projectDir)

        assertEquals(
            listOf("bare-hash", "empty-file", "hand-written"),
            topics.map { it.slug },
        )
        assertTrue(topics.all { it.title == it.slug })
    }

    @Test
    fun `skips files that are not slug-named markdown`() {
        write(projectDir, "README.txt", "# Not markdown\n")
        write(projectDir, "Uppercase.md", "# Not a slug\n")
        write(projectDir, "under_score.md", "# Not a slug either\n")
        write(projectDir, "good-one.md", "# Kept\n")
        Files.createDirectory(projectDir.resolve("a-directory.md"))

        assertEquals(listOf("good-one"), MemoryIndex.topics(projectDir).map { it.slug })
    }

    @Test
    fun `truncates past the character budget and says how many were dropped`() {
        val topics = (1..200).map { MemoryTopic("topic-$it", "A reasonably wordy title for topic $it") }

        val message = assertNotNull(MemoryIndex.format(topics, emptyList()))

        assertTrue(
            message.length < MemoryIndex.MAX_INDEX_CHARS + 400,
            "index was ${message.length} chars, budget is ${MemoryIndex.MAX_INDEX_CHARS}",
        )
        assertTrue(Regex("\\[\\d+ more topics not shown]").containsMatchIn(message), message.takeLast(300))
        assertTrue(message.contains("- topic-1: "))
    }

    @Test
    fun `caps an absurdly long title line`() {
        write(projectDir, "long-title.md", "# " + "x".repeat(5_000) + "\n")

        val title = MemoryIndex.topics(projectDir).single().title

        assertTrue(title.length <= 120, "title was ${title.length} chars")
    }
}
