package dev.rooster.plugin.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * One piece of editor context the user chose to send.
 *
 * [content] is snapshotted at attach time, not read at send time, mirroring
 * `EditPreview`: what the user attached is what the model gets, even if they keep
 * editing the file while composing. [startLine]/[endLine] are 1-based and
 * inclusive, or null when the whole file is attached.
 */
data class Attachment(
    val relativePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val content: String,
) {
    /** `main.py:12-34`, or `main.py` for a whole file. */
    fun label(): String = when {
        startLine == null -> relativePath
        startLine == endLine -> "$relativePath:$startLine"
        else -> "$relativePath:$startLine-$endLine"
    }

    /**
     * The block that goes into the outgoing message.
     *
     * Plain labelled text rather than a new wire-format field: `ChatMessage` has
     * exactly one `content` string, and extending the wire shape would need
     * server support this plugin cannot assume. It has to become text before
     * sending regardless, so it becomes text here — which also means the user's
     * own bubble shows verbatim what the model was given.
     */
    fun asPrompt(): String = "--- ${label()} ---\n$content\n--- end ---"
}

/**
 * The editor context queued for the next message, for one project.
 *
 * Deliberately not persisted: an attachment is a thing you picked a moment ago
 * for the sentence you are about to type, not a setting. It is drained when the
 * message is sent.
 */
@Service(Service.Level.PROJECT)
class RoosterAttachments {

    private val pending = mutableListOf<Attachment>()

    /** Listeners are the composer redrawing its chip row. */
    private val listeners = mutableListOf<() -> Unit>()

    fun all(): List<Attachment> = pending.toList()

    fun add(attachment: Attachment) {
        pending += attachment
        changed()
    }

    fun remove(attachment: Attachment) {
        pending -= attachment
        changed()
    }

    /** Returns what was pending and clears it, so one attachment is sent once. */
    fun drain(): List<Attachment> {
        val taken = pending.toList()
        pending.clear()
        changed()
        return taken
    }

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    private fun changed() = listeners.forEach { it() }

    companion object {
        fun getInstance(project: Project): RoosterAttachments =
            project.getService(RoosterAttachments::class.java)
    }
}
