package dev.turbofieldfare.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import dev.turbofieldfare.plugin.client.TurboFieldfareClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The tool window's root panel: currently the server status line, refreshed by a
 * background poll. Chat transcript and composer arrive in Phase 2.
 */
class TurboFieldfarePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TurboFieldfareClient()
    private val statusDot = StatusDot()

    init {
        border = JBUI.Borders.empty(8)
        add(statusDot, BorderLayout.NORTH)
        startStatusPolling()
    }

    private fun startStatusPolling() {
        scope.launch {
            while (isActive) {
                val status = client.status()
                withContext(Dispatchers.EDT) { statusDot.show(status) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private companion object {
        /** Loopback poll, so it is cheap; still slow enough to stay out of the way. */
        const val POLL_INTERVAL_MS = 5_000L
    }
}
