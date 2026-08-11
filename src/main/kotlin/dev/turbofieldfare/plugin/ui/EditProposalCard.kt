package dev.turbofieldfare.plugin.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.turbofieldfare.plugin.edit.ApplyResult
import dev.turbofieldfare.plugin.edit.FileEditApplier
import dev.turbofieldfare.plugin.tools.EditPreview
import dev.turbofieldfare.plugin.tools.PathScope
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
) : JPanel(BorderLayout()) {

    private val stampLabel = JBLabel(stamp())
    private val applyButton = JButton("Apply")

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
            add(stampLabel.apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = if (blocked) JBColor.GRAY else JBColor.foreground()
            })
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JButton("Show diff").apply { addActionListener { showDiff() } })
            add(applyButton.apply {
                isEnabled = !blocked
                toolTipText = if (blocked) "Switch to Act mode to apply changes" else null
                addActionListener { onApply() }
            })
        }

        add(header, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    /**
     * Names the target the way the person approving it needs to read it.
     *
     * A global memory topic's `relativePath` is a bare slug — the only thing its
     * resolver accepts — which on its own gives no hint that Apply writes outside
     * the project, into a directory every other project will read. Approval has to
     * state where the bytes go.
     */
    private fun title(): String {
        val verb = if (preview.isNewFile) "New file" else "Edit"
        return when (preview.scope) {
            PathScope.Project -> "$verb: ${preview.relativePath}"
            PathScope.GlobalMemory -> "$verb: global memory / ${preview.relativePath}"
        }
    }

    private fun summary(): String = "+${preview.addedLines} / -${preview.removedLines} lines"

    private fun stamp(): String =
        if (blocked) "Plan mode — not executed. Nothing was written." else "Not applied yet."

    /**
     * Applying is a human action: this runs because the user clicked Apply on a
     * card the gate already allowed. Nothing the model emits can reach it.
     */
    private fun onApply() {
        when (val result = FileEditApplier.apply(project, preview)) {
            is ApplyResult.Applied -> {
                applyButton.isEnabled = false
                stampLabel.text = "Applied. Undo (Cmd+Z) in the editor reverts it in one step."
                stampLabel.foreground = JBColor.foreground()
            }

            is ApplyResult.Failed -> {
                stampLabel.text = "Not applied: ${result.message}"
                stampLabel.foreground = JBColor.RED
            }
        }
        revalidate()
        repaint()
    }

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
