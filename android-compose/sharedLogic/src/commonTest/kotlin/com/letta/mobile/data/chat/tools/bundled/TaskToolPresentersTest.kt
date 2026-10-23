package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-2ccpv: how letta-code's task, agent, skill, todo and plan-mode tools read, as the approved mockups draw them. */
class TaskToolPresentersTest {
    private val registry = BundledToolPresenters.registry

    private fun call(name: String, arguments: String, result: String? = null, failed: Boolean = false, elapsedMillis: Long? = null) =
        ToolCallFacts(name, arguments, result, failed, elapsedMillis)

    private fun watched(result: String, elapsedMillis: Long) =
        registry.line(call("TaskOutput", """{"task_id":"bash_3"}""", result, elapsedMillis = elapsedMillis)).target

    @Test
    fun taskOutputSaysTheTaskAndItsStatusAndTailsItsOutput() {
        val output = (1..25).joinToString("\n") { "line $it" }
        val result = "<task_id>bash_3</task_id>\n<status>running</status>\n<output>\n$output\n</output>"
        val facts = call("TaskOutput", """{"task_id":"bash_3"}""", result)
        val line = registry.line(facts)
        assertEquals(ToolVerb.Watching, line.verb)
        assertEquals("task bash_3 · running", line.target)
        assertEquals("task bash_3 · running 1m 12s", watched(result, 72_000))
        assertEquals("task bash_3 · running 1h 3m", watched(result, 3_780_000))
        assertEquals("task bash_3 · running", watched(result, 400))
        assertEquals(listOf(ToolPreviewTarget.TaskOutput("bash_3")), line.previews)
        val tail = assertIs<ToolInset.Text>(registry.inset(facts)).text
        assertTrue(tail.startsWith("… 5 earlier lines\nline 6\n"), tail)
        assertTrue(tail.endsWith("line 25"), tail)
    }

    @Test
    fun taskOutputNamesANonZeroExit() {
        val result = "<status>completed</status><exit_code>2</exit_code><output>boom</output>"
        val facts = call("BashOutput", """{"bash_id":"bash_1"}""", result)
        assertEquals("task bash_1 · completed, exit 2", registry.line(facts).target)
        assertEquals(ToolInset.Text("boom"), registry.inset(facts))
    }

    @Test
    fun taskStopIsItsLineWithNothingToOpen() {
        val facts = call("TaskStop", """{"task_id":"bash_3"}""", "Successfully killed shell: bash_3")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Stopped, line.verb)
        assertEquals("task bash_3", line.target)
        assertFalse(line.expandable, "a stopped task is all line")
        assertNull(registry.inset(facts))
    }

    @Test
    fun todoWriteCountsWhatIsDoneAndOpensToTheChecklist() {
        val todos = """[{"content":"Read","status":"completed"},{"content":"Plan","status":"completed"},""" +
            """{"content":"Build","status":"in_progress"},{"content":"Ship","status":"pending"}]"""
        val facts = call("TodoWrite", """{"todos":$todos}""")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Updated, line.verb)
        assertEquals("todos · 2 of 4 done", line.target)
        val checklist = ToolInset.Checklist(
            items = listOf(
                ToolInset.Checklist.Item("Read", done = true),
                ToolInset.Checklist.Item("Plan", done = true),
                ToolInset.Checklist.Item("Build", done = false),
                ToolInset.Checklist.Item("Ship", done = false),
            ),
            current = 2,
        )
        assertEquals(checklist, registry.inset(facts))
        assertTrue(line.expandable)
    }

    @Test
    fun agentSaysWhoItDelegatedToWhatAndHowItWent() {
        val arguments = """{"description":"Audit the build","subagent_type":"explore","prompt":"Look at gradle"}"""
        val running = registry.line(call("Agent", arguments))
        assertEquals(ToolVerb.Delegated, running.verb)
        assertEquals("to explore · \"Audit the build\"", running.target)
        assertEquals(listOf(ToolPreviewTarget.Subagent("")), running.previews)

        val report = "<task-notification><agent_id>agent-sub</agent_id><status>completed</status>" +
            "<usage>tool_uses: 12\nduration_ms: 9000</usage></task-notification>"
        val done = registry.line(call("Agent", arguments, report))
        assertEquals("to explore · \"Audit the build\" · 12 steps · done", done.target)
        assertEquals(listOf(ToolPreviewTarget.Subagent("agent-sub")), done.previews)
        assertEquals(ToolInset.Text("Look at gradle"), registry.inset(call("Agent", arguments, report)))
    }

    @Test
    fun agentReadsTheSubagentIdADispatchReturns() {
        val dispatched = """{"task_id":"t1","subagent_agent_id":"agent-9"}"""
        val facts = call("Agent", """{"description":"Audit","subagent_type":"explore"}""", dispatched)
        assertEquals(listOf(ToolPreviewTarget.Subagent("agent-9")), registry.line(facts).previews)
    }

    @Test
    fun skillSaysWhichSkillAndWhatItDid() {
        val facts = call("Skill", """{"skill":"pdf"}""", "Launching skill: pdf\n\n# PDF\n…")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Used, line.verb)
        assertEquals("skill pdf · Launching skill: pdf", line.target)
        assertEquals("skill pdf", registry.line(call("Skill", """{"skill":"pdf"}""", "Error: unknown skill", failed = true)).target)
    }

    @Test
    fun planModeChangesAreKnownAndLeavingOpensToThePlan() {
        assertTrue(PlanModeToolPresenter.isPlanModeChange("EnterPlanMode"))
        assertTrue(PlanModeToolPresenter.isPlanModeChange("ExitPlanMode"))
        assertFalse(PlanModeToolPresenter.isPlanModeChange("TodoWrite"))
        assertTrue(PlanModeToolPresenter.isLeaving("ExitPlanMode"))
        assertFalse(PlanModeToolPresenter.isLeaving("EnterPlanMode"))
        val exit = call("ExitPlanMode", """{"plan":"# Add the card\n1. Build it"}""")
        assertEquals(ToolVerb.Planned, registry.line(exit).verb)
        assertEquals("Add the card", registry.line(exit).target)
        assertEquals(ToolInset.Text("# Add the card\n1. Build it"), registry.inset(exit))
        assertNull(registry.inset(call("EnterPlanMode", "{}")))
    }
}
