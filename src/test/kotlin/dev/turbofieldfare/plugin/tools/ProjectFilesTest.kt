package dev.turbofieldfare.plugin.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectFilesTest {

    private val root: Path = Files.createTempDirectory("tff-root").toRealPath()
    private val outside: Path = Files.createTempDirectory("tff-outside").toRealPath()

    @Test
    fun `resolves paths inside the project`() {
        root.resolve("src").createDirectories()
        root.resolve("src/main.py").writeText("x = 1\n")

        val resolved = ProjectFiles.resolve(root, "src/main.py")

        assertNotNull(resolved)
        assertTrue(resolved.startsWith(root))
    }

    @Test
    fun `refuses traversal out of the project`() {
        assertNull(ProjectFiles.resolve(root, "../secrets.txt"))
        assertNull(ProjectFiles.resolve(root, "src/../../secrets.txt"))
        assertNull(ProjectFiles.resolve(root, "/etc/passwd"))
        assertNull(ProjectFiles.resolve(root, outside.toString()))
    }

    @Test
    fun `refuses a symlink that points outside the project`() {
        outside.resolve("id_rsa").writeText("PRIVATE KEY")
        val link = root.resolve("innocent-looking")
        Files.createSymbolicLink(link, outside)

        // The literal path looks like it is inside the project; the real path is not.
        assertNull(ProjectFiles.resolve(root, "innocent-looking/id_rsa"))
    }

    @Test
    fun `allows a path that does not exist yet`() {
        // A file the model proposes to create must still be inside the project,
        // but must not be refused merely for being absent.
        assertNotNull(ProjectFiles.resolve(root, "new/file.py"))
        assertNull(ProjectFiles.resolve(root, "../new/file.py"))
    }

    @Test
    fun `detects binary content`() {
        val text = root.resolve("a.txt").also { it.writeText("hello") }
        val binary = root.resolve("a.bin")
        Files.write(binary, byteArrayOf(1, 2, 0, 3))

        assertTrue(ProjectFiles.isProbablyText(text))
        assertTrue(!ProjectFiles.isProbablyText(binary))
    }
}
