package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.turbofieldfare.plugin.settings.TurboFieldfareSettings
import dev.turbofieldfare.plugin.shell.ShellAllowListMatcher
import dev.turbofieldfare.plugin.shell.ShellCommandExecutor
import java.io.File

/** Asks the user to approve a shell command. Implemented by the tool window. */
fun interface ShellApprover {
    /**
     * Suspends until the user answers. `true` means run it.
     *
     * Must default to "no" for anything other than an explicit approval —
     * including the user closing the tool window or the request being cancelled.
     */
    suspend fun approve(command: String, reason: String): Boolean
}

/**
 * `run_shell_command` — runs a command in the project directory.
 *
 * **The most dangerous tool here, and gated twice.**
 *
 * 1. `effectful = true`, so Plan mode refuses it before it is ever reached.
 * 2. In Act mode it still runs only if the command is auto-approved by
 *    [ShellAllowListMatcher] — which rejects anything containing a shell
 *    metacharacter no matter what it matches — or if the user explicitly approves
 *    it on a card.
 *
 * The default is always "ask". There is no path in which an unrecognised command
 * runs because nobody said no.
 */
class RunShellCommandTool(private val approver: ShellApprover) : Tool {

    override val name = "run_shell_command"
    override val description =
        "Run a shell command in the project directory and return its combined output. " +
            "The user must approve anything that is not on their allow-list."
    override val parameters = objectSchema(
        "command" to "The shell command to run, e.g. \"git status\".",
        required = listOf("command"),
    )

    override val effectful = true

    override suspend fun execute(project: Project, arguments: JsonObject): String {
        val command = arguments.get("command")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return "Error: missing required argument \"command\"."

        val workingDirectory = project.basePath?.let(::File)
            ?: return "Error: this project has no directory to run commands in."

        val patterns = TurboFieldfareSettings.getInstance().state.shellAllowList
        if (!ShellAllowListMatcher.isAutoApproved(command, patterns)) {
            val reason = ShellAllowListMatcher.explain(command, patterns)
            if (!approver.approve(command, reason)) {
                return "Refused: the user did not approve running \"$command\". Nothing was run."
            }
        }

        val result = ShellCommandExecutor.run(command, workingDirectory)
        return buildString {
            if (result.timedOut) {
                append("Command timed out after ${ShellCommandExecutor.DEFAULT_TIMEOUT_SECONDS}s and was killed.\n")
            } else {
                append("Exit code: ${result.exitCode}\n")
            }
            append(if (result.output.isBlank()) "(no output)" else result.output)
        }
    }
}
