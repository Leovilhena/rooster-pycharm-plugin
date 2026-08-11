package dev.rooster.plugin.memory

import java.nio.file.Files
import java.nio.file.Path

/** House rules for one project, in its root, written by hand. */
const val PROJECT_INSTRUCTIONS_FILE = "ROOSTER.md"

/**
 * `ROOSTER.md`, loaded into the same system message the memory index goes into.
 *
 * The distinction from memory is who writes it. Memory is proposed by the model
 * and approved a card at a time; this file the user writes directly, in their own
 * editor, and the plugin only ever reads it. That is why there is no tool for it
 * and no card: there is nothing here for the model to change.
 *
 * A fixed root-relative filename needs no confinement logic — `ProjectFiles`
 * exists to stop a *model-supplied* path escaping the project, and this path
 * comes from us. There is nothing in "ROOSTER.md" to traverse with.
 *
 * Null when the file is missing, unreadable or blank, so a project without one
 * spends no tokens saying so.
 */
object ProjectInstructions {

    fun read(root: Path?): String? =
        root?.resolve(PROJECT_INSTRUCTIONS_FILE)
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { runCatching { Files.readString(it) }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
