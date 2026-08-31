package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.AppServerSubagentSnapshotAdapter
import com.letta.mobile.data.model.SubagentParentIdentity
import com.letta.mobile.data.model.SubagentStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * letta-mobile-al6q5: the three mappers that read a subagent's wire `status`
 * must agree on every value in the vocabulary.
 *
 * The regression this locks down: the App Server's terminal value for a
 * SUCCESSFUL subagent is `success` (m6oa1.6 live capture), which
 * [AppServerSubagentSnapshotAdapter] did not recognize. The raw string then
 * reached the chip mapper, matched none of the four canonical constants, and
 * hit the "unknown means still running" fallback — so a finished subagent
 * rendered as perpetually RUNNING.
 */
class SubagentWireStatusVocabularyTest {

    private fun entryWithStatus(status: String) = AppServerSubagentSnapshotAdapter.toEntry(
        Json.parseToJsonElement(
            """{"subagent_id":"subagent-1","tool_call_id":"tool-call-1","status":"$status"}""",
        ) as JsonObject,
        SubagentParentIdentity("parent-conversation-1", "parent-agent-1"),
    )

    @Test
    fun `success normalizes to completed everywhere`() {
        assertEquals(SubagentStatus.COMPLETED, SubagentStatus.normalize("success"))
        assertEquals(SubagentStatus.COMPLETED, assertNotNull(entryWithStatus("success")).status)
        assertEquals(SubagentChipState.COMPLETED, SubagentChipState.fromWireStatus("success"))
    }

    @Test
    fun `adapter and chip state agree across the whole vocabulary`() {
        val expectations = mapOf(
            "running" to SubagentStatus.RUNNING,
            "in_progress" to SubagentStatus.RUNNING,
            "active" to SubagentStatus.RUNNING,
            "pending" to SubagentStatus.RUNNING,
            "queued" to SubagentStatus.RUNNING,
            "success" to SubagentStatus.COMPLETED,
            "succeeded" to SubagentStatus.COMPLETED,
            "completed" to SubagentStatus.COMPLETED,
            "complete" to SubagentStatus.COMPLETED,
            "done" to SubagentStatus.COMPLETED,
            "error" to SubagentStatus.FAILED,
            "errored" to SubagentStatus.FAILED,
            "failed" to SubagentStatus.FAILED,
            "cancelled" to SubagentStatus.CANCELLED,
            "canceled" to SubagentStatus.CANCELLED,
            "killed" to SubagentStatus.CANCELLED,
        )
        expectations.forEach { (wire, canonical) ->
            assertEquals(canonical, SubagentStatus.normalize(wire), "normalize($wire)")
            assertEquals(canonical, assertNotNull(entryWithStatus(wire)).status, "adapter($wire)")
            // The chip state machine keeps its own pending/running distinction,
            // but must round-trip back to the same canonical wire status.
            assertEquals(
                canonical,
                SubagentChipState.fromWireStatus(wire).toWireStatus(),
                "chipState($wire)",
            )
        }
    }

    @Test
    fun `case and whitespace do not defeat normalization`() {
        assertEquals(SubagentStatus.COMPLETED, SubagentStatus.normalize("  SUCCESS "))
        assertEquals(SubagentChipState.COMPLETED, SubagentChipState.fromWireStatus(" Success"))
    }

    @Test
    fun `unknown vocabulary stays observable rather than being invented into a terminal`() {
        assertEquals(null, SubagentStatus.normalize("teleporting"))
        assertEquals("teleporting", assertNotNull(entryWithStatus("teleporting")).status)
        assertEquals(SubagentChipState.OBSERVED, SubagentChipState.fromWireStatus("teleporting"))
    }
}
