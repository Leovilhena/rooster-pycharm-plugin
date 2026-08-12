package dev.rooster.plugin.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.Timer

/**
 * Rooster's phrases while a generation is running.
 *
 * The counter ticks every second; the phrase does not. Cycling words once a
 * second would be the distracting part, not the helpful one.
 */
private val PHRASES = listOf(
    "Rooster is pecking through the code…",
    "Cock-a-doodle-thinking…",
    "Scratching around for an answer…",
    "Roosting on it…",
    "Crowing up a response…",
)

/** How long one phrase stays on screen. */
private const val PHRASE_SECONDS = 5

/** `90` → `"1m 30s"`. Minutes matter here: local generation runs to minutes. */
fun formatElapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

/** Deterministic, so it is testable and so the same second always reads the same. */
fun phraseFor(elapsedSeconds: Int): String =
    PHRASES[(elapsedSeconds / PHRASE_SECONDS) % PHRASES.size]

/**
 * "Roosting on it… (12s)" next to the send button while the model works.
 *
 * Invisible when idle rather than blank-but-present: a permanently reserved
 * strip of chrome to hold text that is usually absent is worse than a label
 * that appears. Driven by a `javax.swing.Timer`, not a coroutine — this is a
 * pure "update a label once a second on the EDT" concern with no reason to
 * reach into the suspend-based generation loop.
 *
 * The point is a local generation at ~5 tokens/second is slow enough that a
 * still panel is indistinguishable from a hung one.
 */
class ThinkingIndicator : JBLabel() {

    private var elapsedSeconds = 0

    private val timer = Timer(1_000) {
        elapsedSeconds++
        refresh()
    }

    init {
        componentStyle = UIUtil.ComponentStyle.SMALL
        isVisible = false
    }

    fun start() {
        elapsedSeconds = 0
        refresh()
        isVisible = true
        timer.start()
    }

    /** Idempotent: called from a `finally` that runs on completion and cancel alike. */
    fun stop() {
        timer.stop()
        isVisible = false
    }

    private fun refresh() {
        text = "${phraseFor(elapsedSeconds)} (${formatElapsed(elapsedSeconds)})"
    }
}
