package dev.turbofieldfare.plugin.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The other safety test. A failure here means a command the user never saw could
 * run because it happened to start with something harmless.
 */
class ShellAllowListMatcherTest {

    private val defaults = ShellAllowListMatcher.SAFE_DEFAULTS

    @Test
    fun `auto-approves plain read-only commands from the defaults`() {
        listOf(
            "git status",
            "git status --short",
            "git diff HEAD",
            "git log --oneline -5",
            "ls",
            "ls -la src",
            "pwd",
            "cat README.md",
            "pytest --collect-only -q",
        ).forEach { assertTrue(ShellAllowListMatcher.isAutoApproved(it, defaults), "should auto-approve: $it") }
    }

    @Test
    fun `never auto-approves a command with a metacharacter, even when it matches`() {
        // Each of these matches "git status*" or "ls*" as a plain string.
        listOf(
            "git status && rm -rf /",
            "git status; rm -rf ~",
            "git status | sh",
            "git status || curl evil.example.com",
            "ls `rm -rf ~`",
            "ls \$(rm -rf ~)",
            "ls > /etc/hosts",
            "ls < /dev/zero",
            "git status\nrm -rf ~",
            "ls & rm -rf ~",
            "ls \${HOME}",
        ).forEach {
            assertFalse(
                ShellAllowListMatcher.isAutoApproved(it, defaults),
                "must NOT auto-approve: $it",
            )
        }
    }

    @Test
    fun `does not auto-approve commands outside the list`() {
        listOf(
            "rm -rf build",
            "pip install requests",
            "curl https://example.com",
            "git push",
            "python setup.py install",
            "",
            "   ",
        ).forEach { assertFalse(ShellAllowListMatcher.isAutoApproved(it, defaults), "must ask: $it") }
    }

    @Test
    fun `globs are anchored at both ends`() {
        // "ls*" must not match a command that merely contains "ls".
        assertFalse(ShellAllowListMatcher.isAutoApproved("please ls", defaults))
        assertTrue(ShellAllowListMatcher.isAutoApproved("ls -l", defaults))
    }

    @Test
    fun `regex patterns are supported and anchored`() {
        val patterns = listOf("re:make (build|test)")
        assertTrue(ShellAllowListMatcher.isAutoApproved("make build", patterns))
        assertTrue(ShellAllowListMatcher.isAutoApproved("make test", patterns))
        assertFalse(ShellAllowListMatcher.isAutoApproved("make build extra", patterns))
        assertFalse(ShellAllowListMatcher.isAutoApproved("sudo make build", patterns))
    }

    @Test
    fun `a malformed regex matches nothing rather than everything`() {
        val patterns = listOf("re:[unclosed")
        assertFalse(ShellAllowListMatcher.isAutoApproved("anything", patterns))
        assertFalse(ShellAllowListMatcher.isAutoApproved("[unclosed", patterns))
    }

    @Test
    fun `an empty allow-list approves nothing`() {
        assertFalse(ShellAllowListMatcher.isAutoApproved("ls", emptyList()))
        assertFalse(ShellAllowListMatcher.isAutoApproved("pwd", listOf("")))
    }

    @Test
    fun `the shipped defaults contain nothing that writes`() {
        val forbidden = listOf("rm", "mv", "cp", "install", "curl", "wget", "chmod", "chown", "push", "sudo", "dd ")
        defaults.forEach { pattern ->
            forbidden.forEach { bad ->
                assertFalse(pattern.contains(bad), "default pattern \"$pattern\" mentions \"$bad\"")
            }
        }
    }

    @Test
    fun `explain says which rule blocked it`() {
        assertTrue(ShellAllowListMatcher.explain("git status && rm -rf /", defaults).contains("metacharacter"))
        assertTrue(ShellAllowListMatcher.explain("rm -rf build", defaults).contains("allow-list"))
        assertTrue(ShellAllowListMatcher.explain("ls", emptyList()).contains("No allow-list"))
    }
}
