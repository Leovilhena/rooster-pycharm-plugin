package dev.turbofieldfare.plugin.memory

/**
 * The only shape a memory topic name may take.
 *
 * This is a trust boundary for global-scope memory. Project memory still goes
 * through `ProjectFiles.resolve()`'s symlink-aware confinement, but the global
 * directory lives outside every project root, so nothing else confines it — the
 * slug rule is what makes `../../.ssh/id_rsa` unrepresentable rather than merely
 * refused. A slug has no `/`, no `.`, and no `..`, so there is nothing in it to
 * traverse with; the plugin appends `.md` itself and never accepts a filename
 * from the model.
 */
private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

/** Long enough to name a topic, short enough to stay a filename. */
const val MAX_SLUG_LENGTH = 60

fun isValidSlug(slug: String): Boolean =
    slug.length <= MAX_SLUG_LENGTH && SLUG.matches(slug)
