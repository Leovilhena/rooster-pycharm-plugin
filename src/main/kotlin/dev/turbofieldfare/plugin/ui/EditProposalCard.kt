package dev.turbofieldfare.plugin.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.turbofieldfare.plugin.tools.EditPreview
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * One proposed file edit, shown in the transcript.
 *
 * When [blocked] (Plan mode refused the edit) the card says so in plain words and
 * Apply is disabled — the user should never have to infer from an absent button
 * whether something was written to disk.
 */
class EditProposalCard(
    private val project: Project,
    private val preview: EditPreview,
    private val blocked: Boolean,
    private val onApply: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 0),
            JBUI.Borders.customLine(JBColor.border(), 1),
        )

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8, 2, 8)
            add(JBLabel(title()))
            add(JBLabel(summary()).apply { componentStyle = UIUtil.ComponentStyle.SMALL })
            add(JBLabel(stamp()).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = if (blocked) JBColor.GRAY else JBColor.foreground()
            })
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JButton("Show diff").apply { addActionListener { showDiff() } })
            add(JButton("Apply").apply {
                isEnabled = !blocked && onApply != null
                toolTipText = if (blocked) "Switch to Act mode to apply changes" else null
                addActionListener { onApply?.invoke() }
            })
        }

        add(header, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun title(): String =
        if (preview.isNewFile) "New file: ${preview.relativePath}" else "Edit: ${preview.relativePath}"

    private fun summary(): String = "+${preview.addedLines} / -${preview.removedLines} lines"

    private fun stamp(): String =
        if (blocked) "Plan mode — not executed. Nothing was written." else "Not applied yet."

    private fun showDiff() {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Proposed change: ${preview.relativePath}",
            factory.create(project, preview.oldContent),
            factory.create(project, preview.newContent),
            "Current",
            "Proposed",
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
