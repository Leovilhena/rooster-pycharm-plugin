package dev.rooster.plugin.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * The font for chat prose: the IDE's own label font, one point up.
 *
 * `JBFont.label()` rather than Swing's raw default so the text tracks the IDE's
 * theme and font-size setting instead of ignoring both. The extra point is only
 * for prose read at length — chrome (headers, footers, status lines) keeps the
 * platform's `UIUtil.ComponentStyle.SMALL` convention.
 */
fun chatFont(): java.awt.Font = JBFont.label().let { it.deriveFont(it.size + 1f) }

/**
 * The conversation, as a vertical stack of blocks.
 *
 * A plain text area would be simpler, but approval and diff cards need real
 * buttons, and a button the user can click is the whole point of the approval
 * flow — so the transcript has to hold components, not just text.
 */
class TranscriptPanel(private val lastAssistantText: () -> String? = { null }) : JPanel(), Scrollable {

    private var currentText: JBTextArea? = null
    private var currentBubble: MessageBubble? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }

    /**
     * Appends to the block currently being written, starting one if needed.
     *
     * This is the panel's own voice — `[loaded ROOSTER.md]`, mode switches, the
     * context warning — which is nobody's conversational turn and so gets no
     * bubble. Model prose goes through [appendAssistant] instead.
     */
    fun appendText(text: String) {
        val area = currentText ?: newTextBlock()
        area.append(text)
        revalidate()
    }

    /** The user's turn, complete at the moment it is shown. */
    fun startUserMessage(text: String) {
        addBubble(MessageBubble.user()).body.text = text
    }

    /** Opens Rooster's turn, so the header is there before the first token is. */
    fun startAssistantMessage(): MessageBubble =
        addBubble(MessageBubble.rooster().apply { addCopyButton(lastAssistantText) })

    /**
     * Streams a fragment of model prose into the open Rooster bubble.
     *
     * Opens one if there isn't an open bubble — which is what happens after a
     * card interrupts the turn, so prose resuming after an approval card lands in
     * a fresh bubble rather than leaking into the transcript's own voice.
     */
    fun appendAssistant(text: String) {
        val bubble = currentBubble ?: startAssistantMessage()
        bubble.body.append(text)
        revalidate()
    }

    /** Ends the current block or bubble, so the next append starts a fresh one. */
    fun endTextBlock() {
        currentText = null
        currentBubble = null
    }

    fun addCard(card: JComponent) {
        endTextBlock()
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        add(card)
        revalidate()
        repaint()
    }

    /**
     * Hands every bubble the column width before `BoxLayout` asks it how tall it
     * wants to be — otherwise the first pass answers "one very long line".
     */
    override fun doLayout() {
        val available = width - insets.left - insets.right
        if (available > 0) {
            components.filterIsInstance<MessageBubble>().forEach { it.setColumnWidth(available) }
        }
        super.doLayout()
    }

    /**
     * The transcript is exactly as wide as the viewport, never wider.
     *
     * Without this the viewport uses the view's *preferred* width, and a column of
     * wrapping text areas prefers one very long line — so the chat would scroll
     * sideways instead of wrapping. This is the mechanism that gives every bubble
     * a real width to wrap against.
     */
    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, orientation: Int, direction: Int): Int = r.height

    private fun addBubble(bubble: MessageBubble): MessageBubble {
        endTextBlock()
        add(bubble)
        currentBubble = bubble
        revalidate()
        repaint()
        return bubble
    }

    private fun newTextBlock(): JBTextArea {
        val area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            font = chatFont()
        }
        add(area)
        currentText = area
        return area
    }
}
