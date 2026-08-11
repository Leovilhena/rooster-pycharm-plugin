package dev.rooster.plugin.planmode

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two states a session can be in. There is no third state, and no sub-state. */
enum class PlanModeState {
    /** Effectful tools are refused. The assistant can look, describe, and propose. */
    PLAN,

    /** Effectful tools are allowed to reach their approval step. */
    ACT,
}

/**
 * Holds whether this project's session is in Plan or Act mode.
 *
 * **The invariant this class exists to hold: only a human moves PLAN → ACT.**
 * There is no tool that changes this state, the model is never shown this state
 * as something it can set, and [setByUser] is called from exactly one place — a
 * UI control the user clicked. A model that asks to "switch to Act mode" in
 * prose is asking the *user*, and the user has to click.
 *
 * The reason it is a state machine in Kotlin rather than an instruction in the
 * system prompt: the model is a 4B-class local model reasoning over a long,
 * partly model-authored context. Anything in that context — including text the
 * model read out of a file with `read_file` — can claim the mode is ACT. Prompt
 * text cannot enforce anything; [dev.rooster.plugin.tools.ToolExecutor]
 * reading this value can.
 */
@Service(Service.Level.PROJECT)
class PlanModeStateMachine {

    private val _state = MutableStateFlow(PlanModeState.PLAN)

    /** Observable for the UI. */
    val state: StateFlow<PlanModeState> = _state.asStateFlow()

    fun current(): PlanModeState = _state.value

    /**
     * The only mutator.
     *
     * Named for its precondition rather than its effect: every call site must be
     * a direct response to a user gesture. If you are calling this from anywhere
     * that a model's output can reach, the change is wrong, not the name.
     */
    fun setByUser(state: PlanModeState) {
        _state.value = state
    }

    companion object {
        fun getInstance(project: Project): PlanModeStateMachine =
            project.getService(PlanModeStateMachine::class.java)
    }
}
