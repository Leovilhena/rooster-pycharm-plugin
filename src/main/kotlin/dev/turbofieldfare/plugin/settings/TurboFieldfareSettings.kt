package dev.turbofieldfare.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Serialised settings. Kept a plain mutable bean so `XmlSerializerUtil` can copy it. */
class TurboFieldfareState {
    /** Always a loopback address — see [LocalhostOnlyValidator]. */
    var host: String = "127.0.0.1"
    var port: Int = 8080

    /** Empty means "use whatever the server advertises at /v1/models". */
    var modelId: String = ""

    /** New chat sessions start in Plan mode; only a human click leaves it. */
    var planModeDefaultOnNewSession: Boolean = true

    /**
     * Shell commands that may run without asking, as globs (or `re:` regexes).
     *
     * Pre-populated with read-only commands only. Anything containing a shell
     * metacharacter is refused regardless of what it matches — see
     * [dev.turbofieldfare.plugin.shell.ShellAllowListMatcher].
     */
    var shellAllowList: MutableList<String> =
        dev.turbofieldfare.plugin.shell.ShellAllowListMatcher.SAFE_DEFAULTS.toMutableList()

    /**
     * Inline ghost-text completion. **Off by default.**
     *
     * At the ~5 tok/s this hardware decodes at (and a documented ~54s cold start),
     * as-you-type completion arrives after the user has already typed past it. Opt
     * in knowingly, or leave [completionAutomatic] off and trigger it by hand.
     */
    var completionEnabled: Boolean = false

    /** When false, completions only appear on an explicit trigger, never while typing. */
    var completionAutomatic: Boolean = false

    var completionDebounceMs: Int = 700

    /** Small on purpose: a long completion is a long wait for something usually wrong. */
    var completionMaxTokens: Int = 64

    /**
     * The server's `--max-context`. Used only to warn the user before a
     * conversation silently overflows it; the plugin cannot query it.
     */
    var maxContextTokens: Int = 16_384

    /** How long an approved shell command may run before it is killed. */
    var shellTimeoutSeconds: Int = 60
}

/**
 * Application-level settings for the plugin.
 *
 * Application-level rather than per-project: it describes one local server on
 * this machine, and the user would otherwise re-enter the same port for every
 * project they open.
 */
@State(
    name = "TurboFieldfareSettings",
    storages = [Storage("turbofieldfare.xml")],
)
class TurboFieldfareSettings : PersistentStateComponent<TurboFieldfareState> {

    private var state = TurboFieldfareState()

    override fun getState(): TurboFieldfareState = state

    override fun loadState(loaded: TurboFieldfareState) {
        XmlSerializerUtil.copyBean(loaded, state)
        // Settings files are editable by hand and survive plugin downgrades, so a
        // non-loopback host can arrive here without ever passing through the UI.
        // Refuse it at load time rather than trusting what is on disk.
        if (!LocalhostOnlyValidator.isLocalhost(state.host)) {
            state.host = TurboFieldfareState().host
        }
    }

    /** Base URL of the local server, e.g. `http://127.0.0.1:8080`. */
    fun baseUrl(): String = "http://${state.host}:${state.port}"

    companion object {
        fun getInstance(): TurboFieldfareSettings =
            ApplicationManager.getApplication().getService(TurboFieldfareSettings::class.java)
    }
}
