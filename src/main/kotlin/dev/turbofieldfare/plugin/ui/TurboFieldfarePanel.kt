package dev.turbofieldfare.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The tool window's root panel. Phase 0 stub: proves the plugin loads and the
 * panel renders. Chat transcript, status dot and composer arrive in later phases.
 */
class TurboFieldfarePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    init {
        border = JBUI.Borders.empty(8)
        add(JBLabel("TurboFieldfare — not connected yet."), BorderLayout.NORTH)
    }

    override fun dispose() {
        // Nothing to release yet.
    }
}
