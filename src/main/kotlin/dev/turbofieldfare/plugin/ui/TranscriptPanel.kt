package dev.turbofieldfare.plugin.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The conversation, as a vertical stack of blocks.
 *
 * A plain text area would be simpler, but approval and diff cards need real
 * buttons, and a button the user can click is the whole point of the approval
 * flow — so the transcript has to hold components, not just text.
 */
class TranscriptPanel : JPanel() {

    private var currentText: JBTextArea? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }

    /** Appends to the block currently being written, starting one if needed. */
    fun appendText(text: String) {
        val area = currentText ?: newTextBlock()
        area.append(text)
        revalidate()
    }

    /** Ends the current text block, so the next append starts a fresh one. */
    fun endTextBlock() {
        currentText = null
    }

    fun addCard(card: JComponent) {
        endTextBlock()
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        add(card)
        revalidate()
        repaint()
    }

    private fun newTextBlock(): JBTextArea {
        val area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }
        add(area)
        currentText = area
        return area
    }
}
