package dev.turbofieldfare.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.turbofieldfare.plugin.planmode.PlanModeState
import dev.turbofieldfare.plugin.planmode.PlanModeStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The safety test of this plugin. If these fail, a local model can edit files and
 * run commands in Plan mode.
 */
class GateTest {

    private class FakeTool(
        override val name: String,
        override val effectful: Boolean,
    ) : Tool {
        override val description = "fake"
        override val parameters: JsonObject = objectSchema(required = emptyList())
        override fun execute(project: Project, arguments: JsonObject) = "ran"
    }

    private val readOnly = FakeTool("read_only", effectful = false)
    private val effectful = FakeTool("effectful", effectful = true)

    @Test
    fun `plan mode refuses effectful tools`() {
        val decision = ToolExecutor.gate(effectful, PlanModeState.PLAN)

        val refusal = assertIs<GateDecision.Refuse>(decision)
        assertTrue(refusal.message.contains("Plan mode"))
        assertTrue(refusal.message.contains("NOT executed"))
    }

    @Test
    fun `plan mode allows read-only tools`() {
        assertEquals(GateDecision.Allow, ToolExecutor.gate(readOnly, PlanModeState.PLAN))
    }

    @Test
    fun `act mode allows both`() {
        assertEquals(GateDecision.Allow, ToolExecutor.gate(readOnly, PlanModeState.ACT))
        assertEquals(GateDecision.Allow, ToolExecutor.gate(effectful, PlanModeState.ACT))
    }

    @Test
    fun `the real edit tool is declared effectful`() {
        // A regression here would silently open Plan mode to file writes.
        assertTrue(ProposeEditTool.effectful)
        READ_ONLY_TOOLS.forEach { assertTrue(!it.effectful, "${it.name} must not be effectful") }
    }

    @Test
    fun `state machine starts in plan and only moves when asked`() {
        val machine = PlanModeStateMachine()
        assertEquals(PlanModeState.PLAN, machine.current())

        machine.setByUser(PlanModeState.ACT)
        assertEquals(PlanModeState.ACT, machine.current())

        machine.setByUser(PlanModeState.PLAN)
        assertEquals(PlanModeState.PLAN, machine.current())
    }

    @Test
    fun `the gate ignores everything except the tool flag and the mode`() {
        // Same tool, same mode, called repeatedly with unrelated state churn: the
        // verdict is a pure function of two inputs and nothing else.
        repeat(50) {
            assertIs<GateDecision.Refuse>(ToolExecutor.gate(effectful, PlanModeState.PLAN))
        }
    }
}
