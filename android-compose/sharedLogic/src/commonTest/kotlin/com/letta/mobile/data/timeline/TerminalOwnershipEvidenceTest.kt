package com.letta.mobile.data.timeline

import com.letta.mobile.data.a2ui.A2uiDataModel
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TerminalOwnershipEvidenceTest {
    private val scope = TimelineScope("backend", "conversation", "agent")
    private fun event() = TimelineEvent.Confirmed(
        position = 1.0, otid = "owner", content = "complete answer", serverId = "server",
        messageType = TimelineMessageType.ASSISTANT, date = parseTimelineInstantOrNull("2026-01-01T00:00:00Z")!!,
        runId = "run", stepId = null, seqId = 10,
    )

    @Test
    fun targetedMergeMatchesFullSettlementAcrossMutations() = runTest {
        val body = event()
        val mutations = listOf(
            body, body.copy(content = "complete", seqId = 9),
            body.copy(content = "complete answer plus", seqId = 9),
            body.copy(content = "replacement", seqId = 11),
            body.copy(content = "", seqId = null), body.copy(seqId = null),
            body.copy(serverId = "promoted", runId = "real-run"),
            body.copy(stepId = "step"), body.copy(agentId = "agent"),
            body.copy(date = parseTimelineInstantOrNull("2026-01-01T00:01:00Z")!!),
        )
        for (incoming in mutations) {
            var reads = 0
            val expected = settleTerminalEvent(scope.conversationId, body, incoming)
                .copy(position = body.position, otid = body.otid)
            val actual = mergeOwnedTerminal(scope, TerminalOwnershipEvidence.checkpoint(scope, body), incoming, 1024) { requestedScope, _, budget ->
                assertEquals(scope, requestedScope)
                assertEquals(1024, budget)
                reads++
                body
            }
            assertEquals(1, reads)
            if (expected == body) assertEquals(TerminalEvidenceDecision.Unchanged, actual)
            else assertEquals(expected, assertIs<TerminalEvidenceDecision.Changed>(actual).event)
        }
    }

    @Test
    fun scopeAndOwnershipRejectWithoutReading() = runTest {
        val body = event()
        val owner = TerminalOwnershipEvidence.checkpoint(scope, body)
        for ((requestedScope, incoming) in listOf(
            scope.copy(backendId = "other") to body,
            scope.copy(agentId = "other") to body,
            scope.copy(conversationId = "other") to body,
            scope to body.copy(serverId = "foreign", otid = "foreign"),
            scope to body.copy(messageType = TimelineMessageType.REASONING),
        )) {
            assertIs<TerminalEvidenceDecision.Unavailable>(mergeOwnedTerminal(requestedScope, owner, incoming, 10) { _, _, _ -> error("must not read") })
        }
    }

    @Test
    fun missingChangedAndCancelledBodiesNeverProduceWrites() = runTest {
        val body = event()
        val owner = TerminalOwnershipEvidence.checkpoint(scope, body)
        for (loaded in listOf(null, body.copy(seqId = 11), body.copy(runId = "other"), body.copy(otid = "other"), body.copy(serverId = "other"))) {
            assertIs<TerminalEvidenceDecision.Unavailable>(mergeOwnedTerminal(scope, owner, body, 10) { _, _, _ -> loaded })
        }
        assertFailsWith<CancellationException> {
            mergeOwnedTerminal(scope, owner, body, 10) { _, _, _ -> throw CancellationException("cancelled") }
        }
    }

    @Test
    fun suppressionRetainsLegacyPrefixNotWholeContentEquality() {
        val original = event().copy(content = "x".repeat(256) + "first suffix")
        val timeline = Timeline(conversationId = scope.conversationId,
            abandonedAssistantFragmentSuppressions = kotlinx.collections.immutable.persistentSetOf(original.toAbandonedAssistantFragmentSuppression()))
        val checkpoint = AbandonedFragmentEvidence.checkpoint(scope, timeline)
        for (candidate in listOf(original, original.copy(content = "  " + "x".repeat(256) + "different suffix  "),
            original.copy(runId = "other"), original.copy(serverId = "other"),
            original.copy(messageType = TimelineMessageType.USER), original.copy(content = "short"))) {
            assertEquals(timeline.shouldSuppressAbandonedAssistantFragment(candidate), checkpoint.suppresses(scope, candidate))
        }
        assertEquals(false, checkpoint.suppresses(scope.copy(agentId = "other"), original))
    }

    @Test
    fun a2uiCheckpointSurvivesModelAndSourceCollectionMutation() {
        val source = mutableMapOf("value" to JsonPrimitive("before"))
        val model = A2uiDataModel(JsonObject(source))
        val checkpoint = model.checkpoint()
        source["value"] = JsonPrimitive("external mutation")
        model.applyPatch("/value", JsonPrimitive("model mutation"))
        assertEquals("{\"value\":\"before\"}", checkpoint)
    }
}
