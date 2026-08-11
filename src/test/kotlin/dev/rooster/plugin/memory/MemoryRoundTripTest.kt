package dev.rooster.plugin.memory

import dev.rooster.plugin.tools.WriteMemoryTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a memory write promises the next session.
 *
 * The write itself goes through the IDE's Document and undo stack, which needs a
 * platform fixture this repo does not use; what is checked here is everything on
 * either side of that one call — the bytes the tool produces, where they land,
 * and that the next session's index and fetch both agree with them. A write that
 * saved correctly but indexed under a different title, or under a name the read
 * tool cannot ask for, is the failure this covers.
 */
class MemoryRoundTripTest {

    private val globalRoot: Path = Files.createTempDirectory("tff-roundtrip").toRealPath()

    @Test
    fun `a written topic comes back with the title it was given`() {
        val path = assertNotNull(GlobalMemory.resolve(globalRoot, "prefers-early-returns"))
        path.writeText(
            WriteMemoryTool.body(
                title = "Prefer early returns over nested if/else",
                content = "When a function has a guard condition, return early.",
            )
        )

        val topics = MemoryIndex.topics(globalRoot)

        assertEquals(1, topics.size)
        assertEquals("prefers-early-returns", topics[0].slug)
        assertEquals("Prefer early returns over nested if/else", topics[0].title)
        assertTrue(path.readText().startsWith("# Prefer early returns over nested if/else\n\n"))
    }

    @Test
    fun `the title line is the tool's, not the model's`() {
        // The model is told not to write a heading, but the index reads line 1 and
        // must not depend on it having obeyed.
        val text = WriteMemoryTool.body(title = "The real title", content = "  body text  ")

        assertEquals("# The real title\n\nbody text\n", text)
        assertEquals("The real title", MemoryIndex.titleOf("some-slug", text.lines().first()))
    }

    @Test
    fun `rewriting a topic replaces it rather than appending`() {
        val path = assertNotNull(GlobalMemory.resolve(globalRoot, "testing-conventions"))
        path.writeText(WriteMemoryTool.body("We use unittest", "Old and wrong."))
        path.writeText(WriteMemoryTool.body("We use pytest, not unittest", "New and right."))

        assertEquals(
            listOf("We use pytest, not unittest"),
            MemoryIndex.topics(globalRoot).map { it.title },
        )
        assertTrue(!path.readText().contains("unittest\n\nOld"))
    }
}
