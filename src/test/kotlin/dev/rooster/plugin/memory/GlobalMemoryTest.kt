package dev.rooster.plugin.memory

import dev.rooster.plugin.tools.ProjectFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalMemoryTest {

    private val root: Path = Files.createTempDirectory("tff-global-memory").toRealPath()

    @Test
    fun `resolves a valid slug to a markdown file in the root`() {
        val path = assertNotNull(GlobalMemory.resolve(root, "prefers-early-returns"))

        assertEquals(root.resolve("prefers-early-returns.md"), path)
    }

    @Test
    fun `refuses anything that is not a slug`() {
        // No path is ever accepted here, so there is no traversal to resolve: the
        // slug rule is the confinement, not a check applied after one.
        assertNull(GlobalMemory.resolve(root, "../../.ssh/id_rsa"))
        assertNull(GlobalMemory.resolve(root, "/etc/passwd"))
        assertNull(GlobalMemory.resolve(root, ".."))
        assertNull(GlobalMemory.resolve(root, "nested/topic"))
        assertNull(GlobalMemory.resolve(root, "topic.md"))
        assertNull(GlobalMemory.resolve(root, ""))
    }

    @Test
    fun `project-scope memory needs no new confinement logic`() {
        // The project side is deliberately just another project-relative path
        // through the existing trust boundary; asserted so a future refactor that
        // "unifies" the two has to break this test first.
        val projectRoot = Files.createTempDirectory("tff-project").toRealPath()

        val resolved = ProjectFiles.resolve(projectRoot, ".turbofieldfare/memory/testing-conventions.md")

        assertNotNull(resolved)
        assertTrue(resolved.startsWith(projectRoot))
        assertNull(ProjectFiles.resolve(projectRoot, ".turbofieldfare/memory/../../../escape.md"))
    }
}
