package dev.turbofieldfare.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import dev.turbofieldfare.plugin.client.ServerStatus

/**
 * "● Connected" / "● Not connected" line at the top of the tool window.
 *
 * Deliberately a plain label rather than a custom-painted component: the dot is a
 * bullet character, so it inherits the IDE's font metrics and theme colours.
 */
class StatusDot : JBLabel() {

    init {
        show(null)
    }

    /** [status] of `null` means "checking…" (first poll not finished yet). */
    fun show(status: ServerStatus?) {
        when (status) {
            null -> {
                foreground = JBColor.GRAY
                text = "● Checking local server…"
                toolTipText = null
            }

            is ServerStatus.Up -> {
                foreground = CONNECTED
                text = "● Connected"
                toolTipText = if (status.models.isEmpty()) {
                    "Server is up but listed no models"
                } else {
                    "Serving: " + status.models.joinToString(", ")
                }
            }

            is ServerStatus.Down -> {
                foreground = JBColor.GRAY
                // The reason is shown inline, not only in a tooltip: "not connected"
                // on its own sends people hunting through logs.
                text = "● Not connected — ${status.reason}"
                toolTipText = status.reason
            }
        }
    }

    private companion object {
        // Not JBColor.GREEN: that one is close to unreadable on the dark theme.
        val CONNECTED = JBColor(0x59A869, 0x499C54)
    }
}
