package dev.turbofieldfare.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.turbofieldfare.plugin.chat.ChatSession
import dev.turbofieldfare.plugin.client.ChatRequest
import dev.turbofieldfare.plugin.client.ServerStatus
import dev.turbofieldfare.plugin.client.StreamEvent
import dev.turbofieldfare.plugin.client.TurboFieldfareClient
import dev.turbofieldfare.plugin.settings.TurboFieldfareSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * The tool window's root panel: server status, transcript, composer.
 *
 * All model traffic runs on [scope] (a background dispatcher); the only work done
 * on the EDT is appending text, so a slow generation never freezes the IDE.
 */
class TurboFieldfarePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * Rebuilt when the configured server URL changes, so a port change in
     * Settings takes effect without restarting the IDE — but not rebuilt per
     * request, since each client owns HTTP threads.
     */
    private var cachedClient: Pair<String, TurboFieldfareClient>? = null
    private val session = ChatSession()

    private val statusDot = StatusDot()
    private val transcript = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val composer = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")

    /** Model id last advertised by the server; null until the first successful poll. */
    @Volatile
    private var serverModel: String? = null

    /** Non-null exactly while a generation is in flight. */
    private var generation: Job? = null

    init {
        border = JBUI.Borders.empty(8)
        add(statusDot, BorderLayout.NORTH)
        add(JBScrollPane(transcript), BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        sendButton.addActionListener { onSendOrCancel() }
        bindEnterToSend()
        startStatusPolling()
    }

    private fun buildComposer(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(JBScrollPane(composer), BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
    }

    /** Enter sends, Shift+Enter inserts a newline — the convention people expect. */
    private fun bindEnterToSend() {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        composer.inputMap.put(enter, "turbofieldfare.send")
        composer.actionMap.put("turbofieldfare.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSendOrCancel()
        })
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        composer.inputMap.put(shiftEnter, "insert-break")
    }

    private fun onSendOrCancel() {
        // isActive, not just non-null: a very short generation can finish before
        // `generation` is even assigned, and a click must not cancel a dead job
        // and then silently do nothing.
        generation?.takeIf { it.isActive }?.let {
            it.cancel()
            return
        }
        val text = composer.text.trim()
        if (text.isEmpty()) return
        composer.text = ""
        send(text)
    }

    private fun send(text: String) {
        session.addUser(text)
        append("\nYou: $text\n\nAssistant: ")
        setGenerating(true)

        val configuredModel = TurboFieldfareSettings.getInstance().state.modelId.ifBlank { null }
        val request = ChatRequest(
            model = configuredModel ?: serverModel ?: FALLBACK_MODEL,
            messages = session.history(),
        )

        generation = scope.launch {
            val reply = StringBuilder()
            try {
                client().streamChat(request).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            reply.append(event.text)
                            withContext(Dispatchers.EDT) { append(event.text) }
                        }

                        is StreamEvent.Finished -> {
                            if (event.reason == "length") {
                                withContext(Dispatchers.EDT) { append("\n[stopped: hit the token limit]") }
                            }
                        }

                        is StreamEvent.Failed ->
                            withContext(Dispatchers.EDT) { append("\n[error: ${event.message}]") }
                    }
                }
                if (reply.isNotEmpty()) session.addAssistant(reply.toString())
            } finally {
                // Runs on cancellation too, so the button always returns to "Send".
                withContext(Dispatchers.EDT + kotlinx.coroutines.NonCancellable) {
                    if (reply.isEmpty()) append("[cancelled]")
                    append("\n")
                    setGenerating(false)
                    generation = null
                }
            }
        }
    }

    private fun client(): TurboFieldfareClient {
        val url = TurboFieldfareSettings.getInstance().baseUrl()
        cachedClient?.takeIf { it.first == url }?.let { return it.second }
        return TurboFieldfareClient(url).also { cachedClient = url to it }
    }

    private fun setGenerating(generating: Boolean) {
        sendButton.text = if (generating) "Cancel" else "Send"
    }

    private fun append(text: String) {
        transcript.append(text)
        transcript.caretPosition = transcript.document.length
    }

    private fun startStatusPolling() {
        scope.launch {
            while (isActive) {
                val status = client().status()
                if (status is ServerStatus.Up) serverModel = status.models.firstOrNull()
                withContext(Dispatchers.EDT) { statusDot.show(status) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L

        /** Used only if the server hasn't answered `/v1/models` yet. */
        const val FALLBACK_MODEL = "gemma-4-26b-a4b-it"
    }
}
