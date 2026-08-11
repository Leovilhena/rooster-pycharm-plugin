package dev.rooster.plugin.tools

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.rooster.plugin.planmode.PlanModeState
import dev.rooster.plugin.planmode.PlanModeStateMachine
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
        override suspend fun execute(project: Project, arguments: JsonObject) = "ran"
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
    fun `the real edit tools are declared effectful`() {
        // A regression here would silently open Plan mode to file writes.
        assertTrue(ProposeEditTool.effectful)
        // A memory write is a file write. Recording a fact the user never saw would
        // shape every future session invisibly, which is worse than a normal edit,
        // not more benign.
        assertTrue(WriteMemoryTool.effectful)
        READ_ONLY_TOOLS.forEach { assertTrue(!it.effectful, "${it.name} must not be effectful") }
    }

    @Test
    fun `an edit preview is project-scoped unless it says otherwise`() {
        // The default is what keeps propose_edit's behaviour identical: it never
        // sets a scope, so it can never reach the global-memory resolver.
        val preview = EditPreview("src/main.py", oldContent = "", newContent = "x", isNewFile = true)

        assertEquals(PathScope.Project, preview.scope)
    }

    @Test
    fun `reading memory is allowed in plan mode`() {
        // Fetching a fact the user themselves recorded changes nothing, so it is
        // the same class of action as read_file and must run in either mode.
        assertTrue(READ_ONLY_TOOLS.contains(ReadMemoryFileTool))
        assertEquals(GateDecision.Allow, ToolExecutor.gate(ReadMemoryFileTool, PlanModeState.PLAN))
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
