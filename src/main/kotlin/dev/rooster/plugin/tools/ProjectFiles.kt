package dev.rooster.plugin.tools

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Confines every model-supplied path to the open project directory.
 *
 * This is a trust boundary. Without it, `read_file("../../.ssh/id_rsa")` — or
 * `/etc/passwd`, or a symlink planted in the repo pointing at the home
 * directory — is a one-line exfiltration through a tool whose whole purpose is
 * to read files and hand their contents to a model.
 *
 * The check is done on the *real* path (symlinks resolved) after normalisation,
 * because `a/../../b` and a symlinked directory both defeat a naive
 * `startsWith` on the literal string.
 */
object ProjectFiles {

    /** Skipped when listing or searching: noise, and enormous. */
    val IGNORED_DIRECTORIES = setOf(
        ".git", ".hg", ".svn", ".idea", ".gradle", "build", "out", "dist",
        "node_modules", ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache",
        ".tox", "target", ".intellijPlatform",
    )

    /** The project root, or null for a project with no directory (e.g. default project). */
    fun root(project: Project): Path? = project.basePath?.let { Path.of(it) }

    /**
     * Resolves [relative] inside the project, or returns null if it escapes.
     *
     * Null means "refuse", and callers must surface that to the model as an
     * error string rather than falling back to some other path.
     */
    fun resolve(project: Project, relative: String): Path? {
        val root = root(project) ?: return null
        return resolve(root, relative)
    }

    /** Root-relative overload, so the confinement rule is unit-testable without an IDE. */
    fun resolve(root: Path, relative: String): Path? {
        val candidate = try {
            root.resolve(relative).normalize()
        } catch (e: java.nio.file.InvalidPathException) {
            return null
        }

        val realRoot = root.toRealPathOrSelf()
        val realCandidate = candidate.toRealPathOrSelf()
        // Both sides resolved through symlinks before comparing: a symlink inside
        // the project pointing outside it must not count as inside.
        return if (realCandidate.startsWith(realRoot)) candidate else null
    }

    /** Path relative to the project root, for showing back to the model. */
    fun display(project: Project, path: Path): String =
        root(project)?.let { runCatching { it.relativize(path).toString() }.getOrNull() } ?: path.toString()

    fun isIgnored(path: Path): Boolean =
        path.any { it.toString() in IGNORED_DIRECTORIES }

    private fun Path.toRealPathOrSelf(): Path =
        try {
            toRealPath()
        } catch (e: java.io.IOException) {
            // A path that does not exist yet (a file the model wants to create)
            // still has to be inside the project; fall back to the normalised form.
            normalize().toAbsolutePath()
        }

    fun isProbablyText(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val head = ByteArray(1024)
        val read = Files.newInputStream(path).use { it.read(head) }
        if (read <= 0) return true
        // A NUL byte in the first KB is the same heuristic `grep` uses.
        return (0 until read).none { head[it] == 0.toByte() }
    }
}
