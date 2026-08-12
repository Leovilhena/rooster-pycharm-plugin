package dev.rooster.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.rooster.plugin.chat.Attachment
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.JPanel

/**
 * One pending attachment above the composer: `main.py:12-34` and an x.
 *
 * Shows the label, not the content — the content can be a whole file, and the
 * point of the chip is to let the user see at a glance what is about to be sent
 * and drop it if they changed their mind. The full text is visible verbatim in
 * their own message bubble once sent.
 */
class AttachmentChip(attachment: Attachment, onRemove: () -> Unit) :
    JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.emptyRight(4),
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(1, 4),
        )
        add(JBLabel(attachment.label()).apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            toolTipText = "Sent with your next message"
        })
        add(InplaceButton("Remove", AllIcons.Actions.Close, ActionListener { onRemove() }))
    }
}
