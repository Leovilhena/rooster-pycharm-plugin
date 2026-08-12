package dev.rooster.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * One conversational turn, as a bordered block with a "who said it" header.
 *
 * Border and header carry the distinction, not a filled background: a filled
 * bubble means a second per-theme colour to get wrong on top of the border, for
 * no extra information. User turns reuse `JBColor.border()`, already the card
 * border everywhere else in this UI; Rooster turns get one warm amber, chosen to
 * be unmistakably not the green `StatusDot` uses.
 *
 * [body] is the same non-editable wrapping [JBTextArea] the transcript used
 * before bubbles existed, so token-by-token streaming appends into it unchanged.
 */
class MessageBubble private constructor(who: String, accent: Color) : JPanel(BorderLayout()) {

    val body: JBTextArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = chatFont()
    }

    /** Empty and hidden until something is put in it (the copy button). */
    private val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
        isVisible = false
    }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(3, 0),
            JBUI.Borders.customLine(accent, 1),
            JBUI.Borders.empty(4, 8),
        )
        add(
            JBLabel(who).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = accent
            },
            BorderLayout.NORTH,
        )
        add(body, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    private var columnWidth = 0

    /** Set by the transcript before layout, so the first paint already wraps right. */
    fun setColumnWidth(width: Int) {
        columnWidth = width
    }

    fun addFooter(component: Component) {
        footer.add(component)
        footer.isVisible = true
    }

    /**
     * Full width, natural height — and the height has to be the *wrapped* height.
     *
     * A `BoxLayout` column asks for sizes before it assigns widths, and a
     * `lineWrap` text area asked for its preferred size before it has a width
     * answers with one very long line. Clamping the bubble to that is what pins a
     * whole streamed answer to a single clipped row. So the body is told the width
     * it is about to get first, which makes it re-wrap and report a real height.
     */
    override fun getPreferredSize(): Dimension {
        // The column tells us its width before it asks (see TranscriptPanel.doLayout),
        // because on the first pass this bubble has no bounds of its own yet.
        val available = if (columnWidth > 0) columnWidth else width
        if (available > 0) {
            val insets = insets
            body.setSize(available - insets.left - insets.right, Short.MAX_VALUE.toInt())
        }
        return super.getPreferredSize()
    }

    /** Never taller than its content: one turn must not eat the whole column. */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        /** Warm amber, both themes. Deliberately far from `StatusDot`'s green. */
        private val ROOSTER_ACCENT = JBColor(0xB8791F, 0xD9A441)

        fun user(): MessageBubble = MessageBubble("You", JBColor.border())

        fun rooster(): MessageBubble = MessageBubble("Rooster", ROOSTER_ACCENT)
    }
}
