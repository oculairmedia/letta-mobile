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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineMutationParityHarnessTest {
    private val instant = parseTimelineInstant("2026-01-01T00:00:00Z")

    @Test
    fun exactStateEffectNotificationAckTraceIsOrdered() {
        val owner = owner()
        owner.enqueue(TimelineMutation.StreamFrame(1, assistant("reply", "hello")))
        val entry = owner.drainOne()!!

        // Exact trace prevents event-count parity from false-passing reordered, duplicated, or content-drifted effects.
        assertEquals(4, entry.trace().size)
        assertTrue(entry.trace()[0].startsWith("state:events=[confirmed:reply:"))
        assertEquals("effect:event:StreamEventIngested(serverId=reply, messageType=assistant_message)", entry.trace()[1])
        assertEquals("effect:notification:reply:assistant_message:hello", entry.trace()[2])
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

        // Full event semantics catch a dropped mutation even when both schedules retain the same event count.
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
        val call = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single { it.serverId == "call-message" }
        // Pending-map and attached body assertions fail if rebase clears pending returns or applies one twice.
        assertEquals("result", call.toolReturnContentByCallId["call-id"])
        assertTrue(state.pendingToolReturnsByCallId.isEmpty())
        assertEquals(1, owner.journal.flatMap { it.orderedEffects }.count { it.contains("call-message") })
    }

    @Test
    fun staleGenerationCannotReplaceStateOrEmitSideEffects() {
        val owner = owner()
        owner.enqueue(TimelineMutation.HydrateSnapshot(1, 2, listOf(UserMessage("new", JsonPrimitive("new")))))
        owner.enqueue(TimelineMutation.HydrateSnapshot(2, 1, listOf(UserMessage("old", JsonPrimitive("old")))))
        owner.drain()

        // The negative control fails if an older seed replaces newer state or leaks any effect.
        assertEquals(listOf("new"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertEquals(TestAckOutcome.REJECTED, owner.journal.last().ack)
        assertTrue(owner.journal.last().orderedEffects.isEmpty())
    }

    @Test
    fun cleanupSuppressionIsDurableAcrossReplayAndReconcile() {
        val full = confirmed("full", "complete assistant answer", 1.0)
        val fragment = confirmed("fragment", "pa", 2.0)
        val owner = TimelineMutationParityOwner(TimelineReducerState(Timeline("conversation", persistentListOf(full, fragment))))
        owner.enqueue(TimelineMutation.CleanupAbandonedFragments(1, "run", "turn", "test"))
        owner.drain()
        val suppressionAfterCleanup = owner.currentState().timeline.abandonedAssistantFragmentSuppressions
        assertEquals(listOf("full"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        owner.enqueue(TimelineMutation.ReconcileSnapshot(2, 1, listOf(assistant("fragment", "pa"))))
        owner.drain()

        // IDs plus durable suppression metadata catch omitted cleanup even when reconcile itself is enrichment-only.
        assertEquals(listOf("full"), owner.currentState().timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId })
        assertFalse(suppressionAfterCleanup.isEmpty())
        assertEquals(suppressionAfterCleanup, owner.currentState().timeline.abandonedAssistantFragmentSuppressions)
    }

    @Test
    fun closeFailsQueuedAcksAndExposesNoSideChannel() {
        val owner = owner()
        val first = owner.enqueue(TimelineMutation.StreamFrame(1, assistant("one", "one")))
        val second = owner.enqueue(TimelineMutation.StreamFrame(2, assistant("two", "two")))
        owner.close("cancelled")
        val afterClose = owner.enqueue(TimelineMutation.StreamFrame(3, assistant("three", "three")))

        // PENDING is the hanging-ack negative control; an empty journal proves close emits no side channel.
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

        // Identity, cursor, resident dedup metadata, and exact effects catch local/server double rows and incomplete persistence state.
        assertEquals("client", reduction.next.timeline.events.single().otid)
        assertEquals("server", (reduction.next.timeline.events.single() as TimelineEvent.Confirmed).serverId)
        assertEquals("server", reduction.next.timeline.liveCursor)
        assertTrue(reduction.next.timeline.residentOtids.contains("client"))
        assertEquals(3, reduction.effects.size)
    }

    private fun owner() = TimelineMutationParityOwner(TimelineReducerState(Timeline("conversation")))

    private fun assistant(id: String, content: String) = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive(content),
        otid = "otid-$id",
        runId = "run-$id",
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
