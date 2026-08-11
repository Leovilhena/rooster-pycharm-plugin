package dev.turbofieldfare.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.turbofieldfare.plugin.chat.ChatSession
import dev.turbofieldfare.plugin.client.ServerStatus
import dev.turbofieldfare.plugin.client.TurboFieldfareClient
import dev.turbofieldfare.plugin.planmode.PlanModeState
import dev.turbofieldfare.plugin.planmode.PlanModeStateMachine
import dev.turbofieldfare.plugin.settings.TurboFieldfareSettings
import dev.turbofieldfare.plugin.tools.LoopEvent
import dev.turbofieldfare.plugin.tools.ShellApprover
import dev.turbofieldfare.plugin.tools.ToolExecutor
import dev.turbofieldfare.plugin.tools.allTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * The tool window's root panel: status, Plan/Act toggle, transcript, composer.
 *
 * All model traffic runs on [scope]; the only work done on the EDT is updating
 * components, so a slow generation never freezes the IDE.
 */
class TurboFieldfarePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = ChatSession()
    private val planMode = PlanModeStateMachine.getInstance(project)

    private var cachedClient: Pair<String, TurboFieldfareClient>? = null

    private val statusDot = StatusDot()
    private val modeButton = JButton()
    private val transcript = TranscriptPanel()
    private val transcriptScroll = JBScrollPane(transcript)
    private val composer = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")

    @Volatile
    private var serverModel: String? = null

    private var generation: Job? = null

    /** So the context warning is shown once per crossing, not after every turn. */
    private var contextWarningShown = false

    init {
        border = JBUI.Borders.empty(8)
        add(buildHeader(), BorderLayout.NORTH)
        add(transcriptScroll, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        if (!TurboFieldfareSettings.getInstance().state.planModeDefaultOnNewSession) {
            // Even this is a human decision: the user set it in Settings.
            planMode.setByUser(PlanModeState.ACT)
        }
        refreshModeButton()

        sendButton.addActionListener { onSendOrCancel() }
        modeButton.addActionListener { toggleMode() }
        bindEnterToSend()
        startStatusPolling()
    }

    private fun buildHeader(): JComponent = JPanel(BorderLayout()).apply {
        add(statusDot, BorderLayout.WEST)
        add(modeButton, BorderLayout.EAST)
        border = JBUI.Borders.emptyBottom(6)
    }

    private fun buildComposer(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(JBScrollPane(composer), BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
    }

    /**
     * The one and only place the session moves between Plan and Act.
     *
     * It is an `ActionListener` on a button, i.e. it can only run because a human
     * clicked it. No model output reaches this method.
     */
    private fun toggleMode() {
        val next = if (planMode.current() == PlanModeState.PLAN) PlanModeState.ACT else PlanModeState.PLAN
        planMode.setByUser(next)
        refreshModeButton()
        transcript.endTextBlock()
        transcript.appendText("\n[switched to ${next.name} mode]\n")
        transcript.endTextBlock()
    }

    private fun refreshModeButton() {
        val mode = planMode.current()
        modeButton.text = if (mode == PlanModeState.PLAN) "Plan mode" else "Act mode"
        modeButton.toolTipText = when (mode) {
            PlanModeState.PLAN -> "Edits and shell commands are refused. Click to switch to Act mode."
            PlanModeState.ACT -> "Edits and shell commands can be approved. Click to return to Plan mode."
        }
    }

    private fun bindEnterToSend() {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        composer.inputMap.put(enter, "turbofieldfare.send")
        composer.actionMap.put("turbofieldfare.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSendOrCancel()
        })
        composer.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "insert-break",
        )
    }

    private fun onSendOrCancel() {
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
        transcript.endTextBlock()
        transcript.appendText("\nYou: $text\n\nAssistant: ")
        setGenerating(true)

        val configuredModel = TurboFieldfareSettings.getInstance().state.modelId.ifBlank { null }
        val model = configuredModel ?: serverModel ?: FALLBACK_MODEL
        val executor = ToolExecutor(project, session, allTools(approver), planMode)

        generation = scope.launch {
            try {
                executor.run(client(), model) { event ->
                    withContext(Dispatchers.EDT) { render(event) }
                }
            } finally {
                withContext(Dispatchers.EDT + NonCancellable) {
                    transcript.appendText("\n")
                    warnIfContextIsFilling()
                    setGenerating(false)
                }
            }
        }
    }

    /**
     * Shows an approval card and suspends until the user picks one of the two
     * buttons. Nothing else completes the deferred, so silence is never approval;
     * if the panel is disposed while waiting, the scope is cancelled and the tool
     * call is abandoned rather than allowed.
     */
    private val approver = ShellApprover { command, reason ->
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.EDT) {
            transcript.addCard(ShellApprovalCard(command, reason) { approved -> decision.complete(approved) })
            scrollToBottom()
        }
        decision.await()
    }

    private fun render(event: LoopEvent) {
        when (event) {
            is LoopEvent.Text -> transcript.appendText(event.text)
            is LoopEvent.ToolActivity -> {
                transcript.endTextBlock()
                transcript.appendText("  · ${event.detail}\n")
                transcript.endTextBlock()
            }

            is LoopEvent.EditProposed ->
                transcript.addCard(EditProposalCard(project, event.preview, blocked = event.blocked))

            is LoopEvent.Done -> event.reason?.let { transcript.appendText("\n[$it]") }
        }
        scrollToBottom()
    }

    /**
     * Warns once the conversation is close to the server's context window.
     *
     * Without this the failure mode is a sudden HTTP 400 several turns later,
     * which reads as "the plugin broke" rather than "this chat got long".
     */
    private fun warnIfContextIsFilling() {
        val budget = TurboFieldfareSettings.getInstance().state.maxContextTokens
        if (budget <= 0) return
        val used = session.approximateTokens()
        if (used < budget * CONTEXT_WARNING_FRACTION) {
            contextWarningShown = false
            return
        }
        if (contextWarningShown) return
        contextWarningShown = true
        transcript.endTextBlock()
        transcript.appendText(
            "\n[This chat is using roughly $used of the server's ~$budget token context. " +
                "Start a new chat soon, or the server will start refusing requests.]\n"
        )
        transcript.endTextBlock()
    }

    private fun scrollToBottom() {
        transcriptScroll.verticalScrollBar.let { it.value = it.maximum }
    }

    private fun setGenerating(generating: Boolean) {
        sendButton.text = if (generating) "Cancel" else "Send"
    }

    private fun client(): TurboFieldfareClient {
        val url = TurboFieldfareSettings.getInstance().baseUrl()
        cachedClient?.takeIf { it.first == url }?.let { return it.second }
        return TurboFieldfareClient(url).also { cachedClient = url to it }
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
        const val FALLBACK_MODEL = "gemma-4-26b-a4b-it"
        const val CONTEXT_WARNING_FRACTION = 0.75
    }
}
