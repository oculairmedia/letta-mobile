package com.letta.mobile.data.repository.subagent

import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-m6oa1.1: unit coverage for the pure Agent-tool_call
 * correlation reducer. Runs on all targets (jvm + android-unit + hostNative)
 * via `:sharedLogic:allTests`. Backtick names MUST NOT contain "()" —
 * Kotlin/Native rejects them (see #868).
 */
class SubagentCorrelatorTest {

    private val fullParent = ParentContext(
        agentId = "agent-parent",
        conversationId = "conv-parent",
        runId = "run-parent",
    )

    @Test
    fun `dispatch records a running entry with provenance and parsed args`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch(
            toolCallId = "tc-1",
            arguments = """{"description":"scout the repo","subagent_type":"researcher"}""",
            parent = fullParent,
        )

        val snapshot = correlator.snapshot()
        assertEquals(1, snapshot.size)
        val entry = snapshot.single()
        assertEquals("tc-1", entry.toolCallId)
        assertEquals("scout the repo", entry.description)
        assertEquals("researcher", entry.subagentType)
        assertEquals(SubagentStatus.RUNNING, entry.status)
        assertEquals("run-parent", entry.parentRunId)
        assertEquals("agent-parent", entry.parentAgentId)
        assertEquals("conv-parent", entry.parentConversationId)
    }

    @Test
    fun `dispatch parses subagentType camelCase alias`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch(
            toolCallId = "tc-camel",
            arguments = """{"description":"d","subagentType":"planner"}""",
            parent = fullParent,
        )
        assertEquals("planner", correlator.snapshot().single().subagentType)
    }

    @Test
    fun `dispatch parses arguments delivered as a JSON encoded string`() {
        val correlator = SubagentCorrelator()
        // Some frames carry `arguments` as a JSON-encoded string, not an object.
        val encoded = "\"{\\\"description\\\":\\\"nested\\\",\\\"subagent_type\\\":\\\"t\\\"}\""
        correlator.onAgentDispatch("tc-str", encoded, fullParent)

        val entry = correlator.snapshot().single()
        assertEquals("nested", entry.description)
        assertEquals("t", entry.subagentType)
    }

    @Test
    fun `re-observing the same dispatch is idempotent and does not duplicate`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch(
            "tc-1",
            """{"description":"first","subagent_type":"researcher"}""",
            fullParent,
        )
        val revAfterFirst = correlator.revision

        // Identical re-observe: no change, no duplicate, revision unchanged.
        correlator.onAgentDispatch(
            "tc-1",
            """{"description":"first","subagent_type":"researcher"}""",
            fullParent,
        )
        assertEquals(1, correlator.snapshot().size)
        assertEquals(revAfterFirst, correlator.revision)
    }

    @Test
    fun `re-observing backfills missing fields without clobbering populated ones`() {
        val correlator = SubagentCorrelator()
        // First frame lacks provenance runId and description.
        correlator.onAgentDispatch(
            "tc-1",
            """{"subagent_type":"researcher"}""",
            ParentContext(agentId = "agent-parent", conversationId = "conv-parent", runId = null),
        )
        var entry = correlator.snapshot().single()
        assertEquals("", entry.description)
        assertNull(entry.parentRunId)

        // Second frame supplies the previously-missing bits — they backfill.
        correlator.onAgentDispatch(
            "tc-1",
            """{"description":"filled in","subagent_type":"researcher"}""",
            fullParent,
        )
        entry = correlator.snapshot().single()
        assertEquals(1, correlator.snapshot().size)
        assertEquals("filled in", entry.description)
        assertEquals("run-parent", entry.parentRunId)
        // Already-populated subagentType is preserved.
        assertEquals("researcher", entry.subagentType)
    }

    @Test
    fun `return marks the entry completed`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch(
            "tc-1",
            """{"description":"d","subagent_type":"researcher"}""",
            fullParent,
        )
        correlator.onAgentReturn("tc-1", fullParent)

        val entry = correlator.snapshot().single()
        assertEquals(SubagentStatus.COMPLETED, entry.status)
        // Provenance survives the terminal transition.
        assertEquals("agent-parent", entry.parentAgentId)
    }

    @Test
    fun `return for an unknown tool call id is ignored`() {
        val correlator = SubagentCorrelator()
        val revBefore = correlator.revision
        correlator.onAgentReturn("never-dispatched", fullParent)

        assertTrue(correlator.snapshot().isEmpty())
        assertEquals(revBefore, correlator.revision)
    }

    @Test
    fun `missing provenance stays null and is never invented`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch(
            "tc-1",
            """{"description":"d","subagent_type":"researcher"}""",
            ParentContext(agentId = null, conversationId = null, runId = null),
        )
        val entry = correlator.snapshot().single()
        assertNull(entry.parentAgentId)
        assertNull(entry.parentConversationId)
        assertNull(entry.parentRunId)
    }

    @Test
    fun `blank or malformed arguments yield empty description and type`() {
        val correlator = SubagentCorrelator()
        correlator.onAgentDispatch("tc-blank", null, fullParent)
        correlator.onAgentDispatch("tc-bad", "not json at all", fullParent)

        correlator.snapshot().forEach { entry ->
            assertEquals("", entry.description)
            assertEquals("", entry.subagentType)
            assertEquals(SubagentStatus.RUNNING, entry.status)
        }
    }

    @Test
    fun `revision advances on each real state change`() {
        val correlator = SubagentCorrelator()
        assertEquals(0L, correlator.revision)

        correlator.onAgentDispatch("tc-1", """{"description":"d"}""", fullParent)
        val afterDispatch = correlator.revision
        assertTrue(afterDispatch > 0L)

        correlator.onAgentReturn("tc-1", fullParent)
        assertTrue(correlator.revision > afterDispatch)
    }
}
