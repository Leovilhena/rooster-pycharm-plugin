package dev.rooster.plugin.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** What a finished (or killed) shell command produced. */
data class ShellResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
)

/**
 * Runs an approved shell command in the project directory.
 *
 * Runs it through `/bin/sh -c`, because that is what the user believes they
 * approved when they read the command on the approval card — running the tokens
 * directly would silently change the meaning of quotes and globs. It is also why
 * [ShellAllowListMatcher] refuses to auto-approve anything containing a shell
 * metacharacter.
 *
 * The environment is inherited, stderr is merged into stdout (the model wants the
 * error text, and interleaving order is the useful order), and output is capped:
 * one `find /` would otherwise fill a 16k context window.
 */
object ShellCommandExecutor {

    private const val MAX_OUTPUT_CHARS = 20_000
    const val DEFAULT_TIMEOUT_SECONDS = 60L

    suspend fun run(
        command: String,
        workingDirectory: File,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): ShellResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()

        // Nothing is written to the command's stdin: a command that waits for
        // input would otherwise hang until the timeout with no explanation.
        process.outputStream.close()

        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (output.length < MAX_OUTPUT_CHARS) {
                    output.append(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - output.length))
                }
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext ShellResult(
                exitCode = null,
                output = output.toString().trimTrailing(),
                timedOut = true,
            )
        }

        ShellResult(
            exitCode = process.exitValue(),
            output = output.toString().trimTrailing(),
            timedOut = false,
        )
    }

    private fun String.trimTrailing(): String =
        if (length >= MAX_OUTPUT_CHARS) "$this\n[output truncated at $MAX_OUTPUT_CHARS characters]" else this
}
