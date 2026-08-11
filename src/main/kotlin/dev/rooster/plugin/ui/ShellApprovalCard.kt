package dev.rooster.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Asks the user to approve one shell command.
 *
 * The command is shown verbatim in a selectable text area rather than a label:
 * the user is about to authorise it, so it must be readable in full and
 * copy-pasteable, not truncated with an ellipsis. Neither button is a default,
 * and closing or ignoring the card approves nothing.
 */
class ShellApprovalCard(
    private val command: String,
    private val reason: String,
    private val onDecision: (Boolean) -> Unit,
) : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel(reason).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = JBColor.GRAY
    }
    private val runButton = JButton("Run once")
    private val rejectButton = JButton("Don't run")

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 0),
            JBUI.Borders.customLine(JBColor.border(), 1),
        )

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8, 2, 8)
            add(JBLabel("Run this shell command?"))
            add(JBTextArea(command).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4, 0)
            })
            add(statusLabel)
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(runButton.apply { addActionListener { decide(true) } })
            add(rejectButton.apply { addActionListener { decide(false) } })
        }

        add(header, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun decide(approved: Boolean) {
        runButton.isEnabled = false
        rejectButton.isEnabled = false
        statusLabel.text = if (approved) "Approved — running once." else "Not run."
        statusLabel.foreground = JBColor.foreground()
        revalidate()
        repaint()
        onDecision(approved)
    }
}
