package dev.turbofieldfare.plugin.shell

/**
 * Decides whether a shell command may run **without asking the user**.
 *
 * This is a trust boundary, and it is deliberately hard to get a "yes" out of.
 * Two rules, in this order:
 *
 * 1. **Any shell metacharacter disqualifies the command outright**, before any
 *    pattern is considered. Commands are run through `/bin/sh -c`, so
 *    `git status && rm -rf ~` is two commands, and a pattern like `git status*`
 *    matches the whole string. Checking the pattern first would auto-approve the
 *    `rm`. This rule is what makes the allow-list mean what a user thinks it
 *    means: "this command", not "anything beginning with this command".
 * 2. Only then, the command must match one of the user's patterns.
 *
 * A rejection here is never a refusal to run — it falls through to manual
 * approval. The cost of a false negative is one extra click; the cost of a false
 * positive is arbitrary code execution requested by a 4B model.
 */
object ShellAllowListMatcher {

    /**
     * Substrings that disqualify a command from auto-approval.
     *
     * Beyond the obvious separators this includes redirections and newlines:
     * `cat foo > ~/.zshrc` destroys a file without any of `&&;|` appearing, and a
     * newline is a command separator to `sh` just as much as `;` is.
     */
    val METACHARACTERS = listOf(
        "&&", "||", ";", "|", "&",
        "`", "$(", "\${", "\$((",
        ">", "<",
        "\n", "\r",
    )

    /** True if [command] contains anything that could chain, redirect or expand. */
    fun containsMetacharacters(command: String): Boolean =
        METACHARACTERS.any { command.contains(it) }

    /**
     * Whether [command] may run without asking.
     *
     * [patterns] are globs by default (`*` and `?`), or regular expressions when
     * prefixed with `re:`. A malformed regex matches nothing rather than
     * everything — a broken pattern must not become a wildcard.
     */
    fun isAutoApproved(command: String, patterns: List<String>): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        // Rule 1, before anything else.
        if (containsMetacharacters(trimmed)) return false
        return patterns.any { matches(trimmed, it.trim()) }
    }

    /** Why a command was not auto-approved, for the approval card. */
    fun explain(command: String, patterns: List<String>): String = when {
        containsMetacharacters(command.trim()) ->
            "Contains a shell metacharacter, so it can never be auto-approved."

        patterns.isEmpty() -> "No allow-list patterns are configured."
        else -> "Does not match any allow-list pattern."
    }

    private fun matches(command: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        val regex = if (pattern.startsWith(REGEX_PREFIX)) {
            runCatching { Regex(pattern.removePrefix(REGEX_PREFIX)) }.getOrNull() ?: return false
        } else {
            runCatching { Regex(globToRegex(pattern)) }.getOrNull() ?: return false
        }
        return regex.matches(command)
    }

    /** Translates a glob to an anchored regex. Everything else is taken literally. */
    private fun globToRegex(glob: String): String = buildString {
        for (ch in glob) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }

    private const val REGEX_PREFIX = "re:"

    /**
     * Shipped defaults. Every one of these only reads: nothing writes, deletes,
     * installs, or touches the network. A user adding to this list is making a
     * decision; the shipped list must not make it for them.
     */
    val SAFE_DEFAULTS = listOf(
        "git status*",
        "git diff*",
        "git log*",
        "git branch",
        "ls*",
        "pwd",
        "cat *",
        "head *",
        "tail *",
        "wc *",
        "python --version",
        "python3 --version",
        "pytest --collect-only*",
    )
}
