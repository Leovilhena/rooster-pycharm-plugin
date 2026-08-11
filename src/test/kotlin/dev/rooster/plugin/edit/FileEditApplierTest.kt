package dev.rooster.plugin.edit

import dev.rooster.plugin.tools.EditPreview
import dev.rooster.plugin.tools.PathScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The last check before bytes hit the disk.
 *
 * Only [FileEditApplier.resolve] is covered here — the write itself needs a real
 * Document and undo stack, and this repo deliberately runs plain JUnit with no
 * platform fixture (see "Known Deviations from Plan"). What is testable without
 * an IDE is the part that decides *whether* to write and *where*, which is the
 * part that matters if it regresses.
 */
class FileEditApplierTest {

    private val projectRoot: Path = Files.createTempDirectory("tff-apply-project").toRealPath()
    private val globalRoot: Path = Files.createTempDirectory("tff-apply-global").toRealPath()

    private fun preview(path: String, scope: PathScope) =
        EditPreview(path, oldContent = "", newContent = "x", isNewFile = true, scope = scope)

    @Test
    fun `project scope still resolves inside the project`() {
        val resolved = FileEditApplier.resolve(projectRoot, globalRoot, preview("src/main.py", PathScope.Project))

        assertEquals(projectRoot.resolve("src/main.py"), resolved)
    }

    @Test
    fun `project scope still refuses an escape`() {
        assertNull(FileEditApplier.resolve(projectRoot, globalRoot, preview("../escape.py", PathScope.Project)))
        assertNull(FileEditApplier.resolve(projectRoot, globalRoot, preview("/etc/passwd", PathScope.Project)))
        // No project directory at all is a refusal, not a fallback to somewhere else.
        assertNull(FileEditApplier.resolve(null, globalRoot, preview("src/main.py", PathScope.Project)))
    }

    @Test
    fun `global memory scope resolves a slug into the global directory`() {
        val resolved = FileEditApplier.resolve(
            projectRoot, globalRoot, preview("prefers-early-returns", PathScope.GlobalMemory),
        )

        assertEquals(globalRoot.resolve("prefers-early-returns.md"), resolved)
    }

    @Test
    fun `global memory scope re-checks the slug rather than trusting the preview`() {
        // The preview is built from model output. If this branch ever stopped
        // checking, a crafted "topic" would write anywhere the IDE can reach — and
        // this is the last code that could have said no.
        for (bad in listOf("../../.ssh/authorized_keys", "/etc/passwd", "..", "a/b", "Uppercase", "")) {
            assertNull(
                FileEditApplier.resolve(projectRoot, globalRoot, preview(bad, PathScope.GlobalMemory)),
                "global memory scope must refuse \"$bad\"",
            )
        }
    }

    @Test
    fun `the scopes cannot be confused for one another`() {
        // A project-relative memory path is meaningless as a slug, and a slug is
        // not a project path: neither branch can accidentally serve the other.
        assertNull(
            FileEditApplier.resolve(
                projectRoot, globalRoot,
                preview(".rooster/memory/topic.md", PathScope.GlobalMemory),
            ),
        )
        assertEquals(
            projectRoot.resolve("topic"),
            FileEditApplier.resolve(projectRoot, globalRoot, preview("topic", PathScope.Project)),
        )
    }
}
