package dev.rooster.plugin.memory

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/**
 * Where memory that applies to *every* project lives.
 *
 * `<PathManager.getConfigPath()>/turbofieldfare/memory/<slug>.md` — the same
 * application-scoped config directory `RoosterSettings` already persists
 * into (as `options/turbofieldfare.xml`). Global rather than per-project for the
 * same reason the settings are: it describes this user on this machine, and
 * re-recording "prefers early returns" once per project is exactly the friction
 * the feature exists to remove.
 *
 * **A trust boundary, and a different one from [dev.rooster.plugin.tools.ProjectFiles].**
 * That class confines an arbitrary relative path to a project root by resolving
 * symlinks; this directory has no project root to be confined to, so the
 * confinement is instead that the *only* thing that can name a file here is a
 * validated slug ([isValidSlug]) with `.md` appended by us. No path arrives from
 * the model at all, so there is no traversal to resolve. Merging the two behind
 * one interface would hide that they are different mechanisms answering
 * different questions.
 */
object GlobalMemory {

    /** The global memory directory. Not created here — writing creates it. */
    fun root(): Path = Path.of(PathManager.getConfigPath(), DIRECTORY, "memory")

    /** The file for [slug], or null when [slug] is not a valid topic name. */
    fun resolve(slug: String): Path? = resolve(root(), slug)

    /** Root-relative overload, so the rule is unit-testable without an IDE. */
    fun resolve(root: Path, slug: String): Path? =
        if (isValidSlug(slug)) root.resolve("$slug.md") else null

    private const val DIRECTORY = "turbofieldfare"
}
