package dev.turbofieldfare.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Settings → Tools → TurboFieldfare. */
class TurboFieldfareConfigurable : Configurable {

    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val modelField = JBTextField()
    private val planModeDefault = JBCheckBox("New chats start in Plan mode")
    private val allowList = com.intellij.ui.components.JBTextArea(8, 40)
    private val completionEnabled = JBCheckBox("Enable inline completion (off by default — slow on this hardware)")
    private val completionAutomatic = JBCheckBox("Suggest automatically while typing (otherwise only on explicit trigger)")
    private val completionDebounce = JBTextField()

    private var root: JComponent? = null

    override fun getDisplayName(): String = "TurboFieldfare"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server host:", hostField)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>Loopback only (127.0.0.1, localhost, ::1). The server has no auth or TLS.</html>"
                ).apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL },
            )
            .addLabeledComponent("Server port:", portField)
            .addLabeledComponent("Model id (blank = ask the server):", modelField)
            .addComponent(planModeDefault)
            .addLabeledComponent(
                "Shell commands that run without asking:",
                com.intellij.ui.components.JBScrollPane(allowList),
                true,
            )
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>One glob per line, or <code>re:</code> for a regex. A command containing " +
                        "<code>&amp;&amp;</code>, <code>;</code>, <code>|</code>, a backtick, " +
                        "<code>$()</code> or a redirection always asks, whatever it matches.</html>"
                ).apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL },
            )
            .addComponent(completionEnabled)
            .addComponent(completionAutomatic)
            .addLabeledComponent("Completion debounce (ms):", completionDebounce)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val state = TurboFieldfareSettings.getInstance().state
        return hostField.text != state.host ||
            portField.text != state.port.toString() ||
            modelField.text != state.modelId ||
            planModeDefault.isSelected != state.planModeDefaultOnNewSession ||
            parsedAllowList() != state.shellAllowList ||
            completionEnabled.isSelected != state.completionEnabled ||
            completionAutomatic.isSelected != state.completionAutomatic ||
            completionDebounce.text != state.completionDebounceMs.toString()
    }

    private fun parsedAllowList(): MutableList<String> =
        allowList.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    override fun apply() {
        val host = hostField.text.trim()
        // Enforced here as well as in the settings loader: this is the path a user
        // actually takes, and the message needs to explain itself.
        LocalhostOnlyValidator.reject(host)?.let { throw ConfigurationException(it, "Invalid server host") }

        val port = portField.text.trim().toIntOrNull()
            ?: throw ConfigurationException("Port must be a number.", "Invalid server port")
        if (port !in 1..65535) {
            throw ConfigurationException("Port must be between 1 and 65535.", "Invalid server port")
        }

        val state = TurboFieldfareSettings.getInstance().state
        state.host = host
        state.port = port
        state.modelId = modelField.text.trim()
        state.planModeDefaultOnNewSession = planModeDefault.isSelected
        state.shellAllowList = parsedAllowList()
        state.completionEnabled = completionEnabled.isSelected
        state.completionAutomatic = completionAutomatic.isSelected
        state.completionDebounceMs = completionDebounce.text.trim().toIntOrNull()?.coerceIn(0, 10_000)
            ?: throw ConfigurationException("Debounce must be a number of milliseconds.", "Invalid debounce")
    }

    override fun reset() {
        val state = TurboFieldfareSettings.getInstance().state
        hostField.text = state.host
        portField.text = state.port.toString()
        modelField.text = state.modelId
        planModeDefault.isSelected = state.planModeDefaultOnNewSession
        allowList.text = state.shellAllowList.joinToString("\n")
        completionEnabled.isSelected = state.completionEnabled
        completionAutomatic.isSelected = state.completionAutomatic
        completionDebounce.text = state.completionDebounceMs.toString()
    }
}
