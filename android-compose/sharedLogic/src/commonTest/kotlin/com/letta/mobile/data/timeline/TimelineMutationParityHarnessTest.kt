package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineMutationParityHarnessTest {
    private val instant = parseTimelineInstant("2026-01-01T00:00:00Z")

    @Test
    fun exactStateEffectNotificationAckTraceIsOrdered() {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(1, assistant("reply", "hello")))
        val entry = owner.drainOne()!!

        assertEquals(4, entry.trace().size)
        assertTrue(entry.trace()[0].startsWith("state:events=[confirmed:reply:"))
        assertEquals("event:StreamEventIngested(serverId=reply, messageType=assistant_message)", entry.trace()[1])
        assertEquals("notification:reply:assistant_message:len=5,hash=5e918d2", entry.trace()[2])
        assertEquals("ack:COMPLETED", entry.trace()[3])
    }

    @Test
    fun hydrationAndStreamSchedulesConvergeWithoutLossOrDoubleApply() {
        val user = UserMessage("user", JsonPrimitive("hello"), date = "2026-01-01T00:00:00Z")
        val reply = assistant("reply", "world")
        val hydrateThenStream = owner().also {
            it.enqueue(TimelineMutation.HydrateSnapshot(1, 1, listOf(user)))
            it.enqueue(TimelineMutation.StreamFrame(2, reply))
            it.drain()
        }
        val streamThenHydrate = owner().also {
            it.enqueue(TimelineMutation.StreamFrame(1, reply))
            it.enqueue(TimelineMutation.HydrateSnapshot(2, 1, listOf(user)))
            it.drain()
        }

        val firstEvents = hydrateThenStream.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        val secondEvents = streamThenHydrate.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(firstEvents.map { listOf(it.serverId, it.otid, it.content, it.messageType, it.runId) }, secondEvents.map { listOf(it.serverId, it.otid, it.content, it.messageType, it.runId) })
        assertEquals(listOf("user", "reply"), firstEvents.map { it.serverId })
        assertEquals(1L, hydrateThenStream.currentState().hydrateGeneration)
        assertEquals(1L, streamThenHydrate.currentState().hydrateGeneration)
    }

    @Test
    fun returnBeforeCallSurvivesRebaseAndAttachesExactlyOnce() {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(1, toolReturn()))
        owner.enqueue(TimelineMutation.HydrateSnapshot(2, 1, listOf(UserMessage("user", JsonPrimitive("seed")))))
        owner.enqueue(TimelineMutation.StreamFrame(3, toolCall()))
        owner.drain()

        val state = owner.currentState()
        val calls = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.serverId == "call-message" }
        assertEquals(1, calls.size)
        assertEquals(mapOf("call-id" to "result"), calls.single().toolReturnContentByCallId)
        assertTrue(state.pendingToolReturnsByCallId.isEmpty())
        assertEquals(1, state.timeline.events.count { it.otid == calls.single().otid })
        assertEquals(1, state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>().count { it.serverId == calls.single().serverId })
    }

    @Test
    fun staleGenerationCannotReplaceStateEmitSideEffectsOrClaimAcceptance() {
        val owner = owner()
        owner.enqueue(TimelineMutation.HydrateSnapshot(1, 2, listOf(UserMessage("new", JsonPrimitive("new")))))
        owner.enqueue(TimelineMutation.HydrateSnapshot(2, 1, listOf(UserMessage("old", JsonPrimitive("old")))))
        owner.drain()

        val rejected = owner.journal.last()
        assertEquals(listOf("new"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertEquals(1L, owner.currentState().lastAppliedMutationSequence)
        assertEquals(2L, rejected.attemptedSequence)
        assertNull(rejected.acceptedSequence)
        assertEquals(TestAckOutcome.REJECTED, rejected.ack)
        assertTrue(rejected.orderedEffects.isEmpty())
    }

    @Test
    fun staleSequenceCannotAdvanceLastAppliedOrClaimAcceptance() {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(2, assistant("new", "new")))
        owner.enqueue(TimelineMutation.StreamFrame(1, assistant("old", "old")))
        owner.drain()

        val rejected = owner.journal.last()
        assertEquals(2L, owner.currentState().lastAppliedMutationSequence)
        assertEquals(1L, rejected.attemptedSequence)
        assertNull(rejected.acceptedSequence)
        assertEquals(TestAckOutcome.REJECTED, rejected.ack)
    }

    @Test
    fun cleanupSuppressionIsDurableAcrossRealReplayMergeAndAllowsControlRow() {
        val full = confirmed("full", "complete assistant answer", 1.0)
        val fragment = confirmed("fragment", "pa", 2.0)
        val owner = TimelineMutationParityOwner(TimelineReducerState(Timeline("conversation", persistentListOf(full, fragment))))
        owner.enqueue(TimelineMutation.CleanupAbandonedFragments(1, "run", "turn", "test"))
        owner.drain()
        val suppressionAfterCleanup = owner.currentState().timeline.abandonedAssistantFragmentSuppressions
        assertEquals(listOf("full"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })

        owner.enqueue(TimelineMutation.ReconcileSnapshot(2, 1, listOf(
            assistant("fragment", "pa", runId = "run", otid = "otid-fragment"),
            assistant("control", "new complete row", runId = "run-control", otid = "otid-control"),
        )))
        owner.drain()

        assertEquals(listOf("full", "control"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertFalse(suppressionAfterCleanup.isEmpty())
        assertEquals(suppressionAfterCleanup, owner.currentState().timeline.abandonedAssistantFragmentSuppressions)
    }

    @Test
    fun duplicateHydrateAndReconcileReportNoChange() {
        val message = UserMessage("user", JsonPrimitive("hello"), date = "2026-01-01T00:00:00Z")
        val hydrateOwner = owner()
        hydrateOwner.enqueue(TimelineMutation.HydrateSnapshot(1, 1, listOf(message)))
        hydrateOwner.enqueue(TimelineMutation.HydrateSnapshot(2, 1, listOf(message)))
        hydrateOwner.drain()
        assertIs<TimelineReductionResult.NoChange>(hydrateOwner.journal.last().result)

        val reconcileOwner = TimelineMutationParityOwner(hydrateOwner.currentState().copy(lastAppliedMutationSequence = 0))
        reconcileOwner.enqueue(TimelineMutation.ReconcileSnapshot(1, 0, listOf(message)))
        reconcileOwner.drain()
        assertIs<TimelineReductionResult.NoChange>(reconcileOwner.journal.last().result)
    }

    @Test
    fun lifecycleResetPreservesBufferedToolReturns() {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(1, toolReturn()))
        owner.enqueue(TimelineMutation.LifecycleReset(2, 1))
        owner.enqueue(TimelineMutation.StreamFrame(3, toolCall()))
        owner.drain()

        val call = owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single { it.serverId == "call-message" }
        assertEquals("result", call.toolReturnContentByCallId["call-id"])
        assertTrue(owner.currentState().pendingToolReturnsByCallId.isEmpty())
    }

    @Test
    fun semanticFingerprintCannotCollideOnPendingReturnDelimiters() {
        val single = pendingReturnState("a", "b", "c,d:e")
        val delimiterShift = pendingReturnState("a:b", "c", "d:e")

        assertNotEquals(single.semanticFingerprint(), delimiterShift.semanticFingerprint())
    }

    @Test
    fun closeFailsQueuedAcksAndExposesNoSideChannel() {
        val owner = owner()
        val first = owner.enqueue(TimelineMutation.StreamFrame(1, assistant("one", "one")))
        val second = owner.enqueue(TimelineMutation.StreamFrame(2, assistant("two", "two")))
        owner.close("cancelled")
        val afterClose = owner.enqueue(TimelineMutation.StreamFrame(3, assistant("three", "three")))

        assertEquals(listOf(TestAckOutcome.FAILED, TestAckOutcome.FAILED, TestAckOutcome.FAILED), listOf(first.outcome, second.outcome, afterClose.outcome))
        assertTrue(owner.journal.isEmpty())
        assertTrue(owner.currentState().timeline.events.isEmpty())
    }

    @Test
    fun localConfirmationPreservesIdentityAndResidentMetadata() {
        val owner = owner()
        owner.enqueue(TimelineMutation.LocalAppend(1, PendingSend("client", "hello"), instant))
        owner.drain()
        val reduction = reducePostSendReconcile(
            owner.currentState(),
            "client",
            listOf(UserMessage("server", JsonPrimitive("hello"), otid = "client", date = "2026-01-01T00:00:01Z")),
        )

        assertEquals("client", reduction.next.timeline.events.single().otid)
        assertEquals("server", (reduction.next.timeline.events.single() as TimelineEvent.Confirmed).serverId)
        assertEquals("server", reduction.next.timeline.liveCursor)
        assertTrue(reduction.next.timeline.residentOtids.contains("client"))
        assertEquals(3, reduction.effects.size)
    }

    @Test
    fun generatedLocalMutationSequencesMatchIndependentSemanticModel() {
        val verifier = LocalMutationParityVerifier()
        repeat(LocalMutationCaseGenerator.DEFAULT_CASES) { seed ->
            verifier.verify(seed.toLong(), LocalMutationCaseGenerator.generate(seed.toLong()))
        }
    }

    @Test
    fun controlledEffectMutantIsDetectedAndShrunkWithSeed() {
        val mutant = LocalMutationParityVerifier { state, mutation ->
            val reduction = reduceProductionMutation(state, mutation)
            if (mutation is TimelineMutation.LocalAppend) reduction.copy(effects = persistentListOf()) else reduction
        }
        val error = assertFailsWith<AssertionError> {
            mutant.verify(
                seed = 73L,
                operations = listOf(
                    LocalSemanticMutation.Reset(1),
                    LocalSemanticMutation.Append("synthetic-id", "synthetic-value"),
                    LocalSemanticMutation.MarkFailed("synthetic-id"),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("seed=73"))
        assertTrue(error.message.orEmpty().contains("shrunk=[Append"))
        assertFalse(error.message.orEmpty().contains("synthetic-value"))
    }

    private fun owner() = TimelineMutationParityOwner(TimelineReducerState(Timeline("conversation")))

    private fun pendingReturnState(callId: String, id: String, response: String): TimelineReducerState {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(1, ToolReturnMessage(
            id = id,
            toolCallId = callId,
            toolReturnRaw = JsonPrimitive(response),
            status = "success",
        )))
        owner.drain()
        return owner.currentState()
    }

    private fun assistant(
        id: String,
        content: String,
        runId: String = "run-$id",
        otid: String = "otid-$id",
    ) = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive(content),
        otid = otid,
        runId = runId,
        date = "2026-01-01T00:00:01Z",
    )

    private fun toolReturn() = ToolReturnMessage(
        id = "return-message",
        toolCallId = "call-id",
        toolReturnRaw = JsonPrimitive("result"),
        status = "success",
        runId = "run-tool",
    )

    private fun toolCall() = ToolCallMessage(
        id = "call-message",
        toolCalls = listOf(ToolCall(toolCallId = "call-id", name = "tool", arguments = "{}")),
        runId = "run-tool",
    )

    private fun confirmed(id: String, content: String, position: Double, stepId: String? = "turn") = TimelineEvent.Confirmed(
        position = position,
        otid = "otid-$id",
        content = content,
        serverId = id,
        messageType = TimelineMessageType.ASSISTANT,
        date = instant,
        runId = "run",
        stepId = stepId,
    )
}
