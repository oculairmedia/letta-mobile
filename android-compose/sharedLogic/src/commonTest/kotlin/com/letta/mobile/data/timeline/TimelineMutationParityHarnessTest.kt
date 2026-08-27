package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
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
    fun exactStateEffectNotificationAckTraceIsOrdered() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(TimelineMutation.StreamFrame(assistantReplyHello()))
        val entry = owner.drainOne()!!

        assertEquals(4, entry.trace().size)
        assertTrue(entry.trace()[0].startsWith("state:"))
        assertTrue(entry.trace()[1].contains("StreamEventIngested"))
        assertTrue(entry.trace()[2].contains("Notify"))
        assertEquals("ack:COMPLETED", entry.trace()[3])
    }

    @Test
    fun hydrationAndStreamSchedulesConvergeWithoutLossOrDoubleApply() = runTest {
        val user = UserMessage("user", JsonPrimitive("hello"), date = "2026-01-01T00:00:00Z")
        val reply = assistantReplyWorld()
        val hydrateThenStream = owner(backgroundScope).also {
            it.enqueue(hydrationMutation(1, listOf(user)))
            it.enqueue(TimelineMutation.StreamFrame(reply))
            it.drain()
        }
        val streamThenHydrate = owner(backgroundScope).also {
            it.enqueue(TimelineMutation.StreamFrame(reply))
            it.enqueue(hydrationMutation(1, listOf(user)))
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
    fun returnBeforeCallSurvivesRebaseAndAttachesExactlyOnce() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(TimelineMutation.StreamFrame(toolReturn()))
        owner.enqueue(hydrationMutation(1, listOf(UserMessage("user", JsonPrimitive("seed")))))
        owner.enqueue(TimelineMutation.StreamFrame(toolCall()))
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
    fun staleGenerationCannotReplaceStateEmitSideEffectsOrClaimAcceptance() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(hydrationMutation(2, listOf(UserMessage("new", JsonPrimitive("new")))))
        owner.enqueue(hydrationMutation(1, listOf(UserMessage("old", JsonPrimitive("old")))))
        owner.drain()

        val rejected = owner.journal.last()
        assertEquals(listOf("new"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertEquals(1L, owner.currentState().lastAppliedMutationSequence)
        assertEquals(2L, rejected.attemptedSequence)
        assertNull(rejected.acceptedSequence)
        assertEquals(TestAckOutcome.REJECTED, rejected.ack)
        assertEquals(
            TimelineJournalAckReason.Rejection(
                TimelineProcessorRejectionReason.StaleGeneration("HydrateSnapshot", 1, 2),
            ),
            rejected.ackReason,
        )
        assertTrue(rejected.orderedEffects.isEmpty())
    }

    @Test
    fun internallyAssignedSequencesFollowSubmissionOrder() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(TimelineMutation.StreamFrame(assistantNew()))
        owner.enqueue(TimelineMutation.StreamFrame(assistantOld()))
        owner.drain()

        val second = owner.journal.last()
        assertEquals(2L, owner.currentState().lastAppliedMutationSequence)
        assertEquals(2L, second.attemptedSequence)
        assertEquals(2L, second.acceptedSequence)
        assertEquals(TestAckOutcome.COMPLETED, second.ack)
        assertEquals(listOf("new", "old"), owner.currentState().timeline.events.map { (it as TimelineEvent.Confirmed).serverId })
    }

    @Test
    fun cleanupSuppressionIsDurableAcrossRealReplayMergeAndAllowsControlRow() = runTest {
        val full = confirmedFull()
        val fragment = confirmedFragment()
        val owner = TimelineMutationParityOwner(
            TimelineReducerState(Timeline("conversation", persistentListOf(full, fragment))),
            backgroundScope,
        )
        owner.enqueue(TimelineMutation.CleanupAbandonedFragments("run", "turn", "test"))
        owner.drain()
        val suppressionAfterCleanup = owner.currentState().timeline.abandonedAssistantFragmentSuppressions
        assertEquals(listOf("full"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })

        owner.enqueue(TimelineMutation.ReconcileSnapshot(1, listOf(
            replayFragment(),
            replayControl(),
        )))
        owner.drain()

        assertEquals(listOf("full", "control"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertFalse(suppressionAfterCleanup.isEmpty())
        assertEquals(suppressionAfterCleanup, owner.currentState().timeline.abandonedAssistantFragmentSuppressions)
    }

    @Test
    fun duplicateHydrateAndReconcileReportNoChange() = runTest {
        val message = UserMessage("user", JsonPrimitive("hello"), date = "2026-01-01T00:00:00Z")
        val hydrateOwner = owner(backgroundScope)
        hydrateOwner.enqueue(hydrationMutation(1, listOf(message)))
        hydrateOwner.enqueue(hydrationMutation(1, listOf(message)))
        hydrateOwner.drain()
        val duplicateHydration = assertIs<TimelineReductionResult.Hydrated>(hydrateOwner.journal.last().result)
        assertFalse(duplicateHydration.changed)

        val reconcileOwner = TimelineMutationParityOwner(
            hydrateOwner.currentState().copy(lastAppliedMutationSequence = 0),
            backgroundScope,
        )
        reconcileOwner.enqueue(TimelineMutation.ReconcileSnapshot(0, listOf(message)))
        reconcileOwner.drain()
        assertIs<TimelineReductionResult.NoChange>(reconcileOwner.journal.last().result)
    }

    @Test
    fun lifecycleResetPreservesBufferedToolReturns() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(TimelineMutation.StreamFrame(toolReturn()))
        owner.enqueue(TimelineMutation.LifecycleReset(1))
        owner.enqueue(TimelineMutation.StreamFrame(toolCall()))
        owner.drain()

        val call = owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single { it.serverId == "call-message" }
        assertEquals("result", call.toolReturnContentByCallId["call-id"])
        assertTrue(owner.currentState().pendingToolReturnsByCallId.isEmpty())
    }

    @Test
    fun semanticFingerprintCannotCollideOnPendingReturnDelimiters() = runTest {
        val single = singlePendingReturnState(backgroundScope)
        val delimiterShift = delimiterShiftPendingReturnState(backgroundScope)

        assertNotEquals(single.semanticFingerprint(), delimiterShift.semanticFingerprint())
    }

    @Test
    fun closeFailsQueuedAcksAndExposesNoSideChannel() = runTest {
        val owner = owner(backgroundScope)
        val first = owner.enqueue(TimelineMutation.StreamFrame(assistantOne()))
        val second = owner.enqueue(TimelineMutation.StreamFrame(assistantTwo()))
        owner.close(TestCloseReason.CANCELLED)
        val afterClose = owner.enqueue(TimelineMutation.StreamFrame(assistantThree()))

        assertEquals(
            listOf(TestAckOutcome.FAILED, TestAckOutcome.FAILED, TestAckOutcome.REJECTED),
            listOf(first.outcome, second.outcome, afterClose.outcome),
        )
        assertTrue(owner.journal.isEmpty())
        assertTrue(owner.currentState().timeline.events.isEmpty())
    }

    @Test
    fun localConfirmationPreservesIdentityAndResidentMetadata() = runTest {
        val owner = owner(backgroundScope)
        owner.enqueue(TimelineMutation.LocalAppend(PendingSend("client", "hello"), instant))
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
    fun generatedLocalMutationSequencesMatchIndependentSemanticModel() = runTest {
        val verifier = LocalMutationParityVerifier()
        repeat(LocalMutationCaseGenerator.DEFAULT_CASES) { seed ->
            verifier.verify(seed.toLong(), LocalMutationCaseGenerator.generate(seed.toLong()))
        }
    }

    @Test
    fun controlledEffectMutantIsDetectedAndShrunkWithSeed() = runTest {
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

    @Test
    fun droppedStateMutantIsDetected() = runTest {
        assertAppendMutantDetected(seed = 11) { state, reduction ->
            reduction.copy(next = state)
        }
    }

    @Test
    fun reorderedEffectMutantIsDetected() = runTest {
        assertAppendMutantDetected(seed = 12) { _, reduction ->
            reduction.copy(effects = reduction.effects.reversed().toTimelinePersistentList())
        }
    }

    @Test
    fun wrongNoOpResultMutantIsDetected() = runTest {
        val mutant = LocalMutationParityVerifier { state, mutation ->
            val reduction = reduceProductionMutation(state, mutation)
            if (!reduction.result.changed) {
                reduction.copy(
                    result = TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
                )
            } else {
                reduction
            }
        }

        assertFailsWith<AssertionError> {
            mutant.verify(
                13,
                listOf(
                    LocalSemanticMutation.Append("id", "first"),
                    LocalSemanticMutation.Append("id", "duplicate"),
                ),
            )
        }
    }

    @Test
    fun wrongRejectionSeedMutantIsExposedByExactAckControl() = runTest {
        val mutant = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            stateBridge = object : TimelineProcessorStateBridge {
                override fun synchronizeSeed(processorState: TimelineReducerState) =
                    processorState.copy(hydrateGeneration = 0)
            },
        )
        mutant.submit(hydrationMutation(2))

        val incorrectlyAccepted = mutant.submit(hydrationMutation(1))

        assertIs<TimelineProcessorAck.Applied>(incorrectlyAccepted)
        assertEquals(2L, incorrectlyAccepted.sequence)
    }

    @Test
    fun pendingResetMutantIsExposedByReturnBeforeCallControl() = runTest {
        val mutant = TimelineMutationParityOwner(
            initial = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            reducer = { state, mutation ->
                val reduction = reduceProductionMutation(state, mutation)
                if (mutation is TimelineMutation.LifecycleReset) {
                    reduction.copy(next = reduction.next.copy(pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf()))
                } else {
                    reduction
                }
            },
        )
        mutant.enqueue(TimelineMutation.StreamFrame(toolReturn()))
        mutant.enqueue(TimelineMutation.LifecycleReset(1))
        mutant.enqueue(TimelineMutation.StreamFrame(toolCall()))
        mutant.drain()

        val call = mutant.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "call-message" }
        assertTrue(call.toolReturnContentByCallId.isEmpty())
    }

    private suspend fun assertAppendMutantDetected(
        seed: Long,
        mutate: (TimelineReducerState, TimelineReduction) -> TimelineReduction,
    ) {
        val mutant = LocalMutationParityVerifier { state, mutation ->
            val reduction = reduceProductionMutation(state, mutation)
            if (mutation is TimelineMutation.LocalAppend) mutate(state, reduction) else reduction
        }
        assertFailsWith<AssertionError> {
            mutant.verify(seed, listOf(LocalSemanticMutation.Append("id", "body")))
        }
    }

    private fun owner(scope: CoroutineScope) = TimelineMutationParityOwner(
        TimelineReducerState(Timeline("conversation")),
        scope,
    )

    private suspend fun singlePendingReturnState(scope: CoroutineScope) = pendingReturnState(
        scope,
        ToolReturnMessage(id = "b", toolCallId = "a", toolReturnRaw = JsonPrimitive("c,d:e"), status = "success"),
    )

    private suspend fun delimiterShiftPendingReturnState(scope: CoroutineScope) = pendingReturnState(
        scope,
        ToolReturnMessage(id = "c", toolCallId = "a:b", toolReturnRaw = JsonPrimitive("d:e"), status = "success"),
    )

    private suspend fun pendingReturnState(scope: CoroutineScope, message: ToolReturnMessage): TimelineReducerState {
        val owner = owner(scope)
        owner.enqueue(TimelineMutation.StreamFrame(message))
        owner.drain()
        return owner.currentState()
    }

    private fun hydrationMutation(
        generation: Long,
        messages: List<com.letta.mobile.data.model.LettaMessage> = emptyList(),
    ) = TimelineMutation.HydrateSnapshot(
        generation = generation,
        messages = messages,
        timelineBeforeFetch = Timeline("conversation"),
        diskRecords = emptyList(),
    )

    private fun assistantReplyHello() = AssistantMessage(
        id = "reply", contentRaw = JsonPrimitive("hello"), otid = "otid-reply", runId = "run-reply", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantReplyWorld() = AssistantMessage(
        id = "reply", contentRaw = JsonPrimitive("world"), otid = "otid-reply", runId = "run-reply", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantNew() = AssistantMessage(
        id = "new", contentRaw = JsonPrimitive("new"), otid = "otid-new", runId = "run-new", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantOld() = AssistantMessage(
        id = "old", contentRaw = JsonPrimitive("old"), otid = "otid-old", runId = "run-old", date = "2026-01-01T00:00:01Z",
    )

    private fun replayFragment() = AssistantMessage(
        id = "fragment", contentRaw = JsonPrimitive("pa"), otid = "otid-fragment", runId = "run", date = "2026-01-01T00:00:01Z",
    )

    private fun replayControl() = AssistantMessage(
        id = "control", contentRaw = JsonPrimitive("new complete row"), otid = "otid-control", runId = "run-control", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantOne() = AssistantMessage(
        id = "one", contentRaw = JsonPrimitive("one"), otid = "otid-one", runId = "run-one", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantTwo() = AssistantMessage(
        id = "two", contentRaw = JsonPrimitive("two"), otid = "otid-two", runId = "run-two", date = "2026-01-01T00:00:01Z",
    )

    private fun assistantThree() = AssistantMessage(
        id = "three", contentRaw = JsonPrimitive("three"), otid = "otid-three", runId = "run-three", date = "2026-01-01T00:00:01Z",
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

    private fun confirmedFull() = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "otid-full",
        content = "complete assistant answer",
        serverId = "full",
        messageType = TimelineMessageType.ASSISTANT,
        date = instant,
        runId = "run",
        stepId = "turn",
    )

    private fun confirmedFragment() = TimelineEvent.Confirmed(
        position = 2.0,
        otid = "otid-fragment",
        content = "pa",
        serverId = "fragment",
        messageType = TimelineMessageType.ASSISTANT,
        date = instant,
        runId = "run",
        stepId = "turn",
    )
}
