package dev.rooster.plugin.memory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectInstructionsTest {

    private val root: Path = Files.createTempDirectory("rooster-instructions").toRealPath()

    private fun write(content: String) {
        Files.writeString(root.resolve(PROJECT_INSTRUCTIONS_FILE), content)
    }

    @Test
    fun `returns the file's content, trimmed`() {
        write("\n  Always use early returns.\n\n")

        assertEquals("Always use early returns.", ProjectInstructions.read(root))
    }

    @Test
    fun `is null when there is no file`() {
        assertNull(ProjectInstructions.read(root))
    }

    @Test
    fun `is null for a blank file`() {
        // Distinct from "missing" only to the filesystem: an empty ROOSTER.md is
        // still nothing to say, and sending it would spend tokens on a heading.
        write("   \n\t\n")

        assertNull(ProjectInstructions.read(root))
    }

    @Test
    fun `is null when there is no project root`() {
        assertNull(ProjectInstructions.read(null))
    }

    @Test
    fun `is null when the name is taken by a directory`() {
        Files.createDirectory(root.resolve(PROJECT_INSTRUCTIONS_FILE))

        assertNull(ProjectInstructions.read(root))
    }
}
