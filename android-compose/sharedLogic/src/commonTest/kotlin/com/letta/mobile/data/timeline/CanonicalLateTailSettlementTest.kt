package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays the frame order a Pixel 9 Pro received on 2026-09-24 (conversation local-conv-496,
 * run local-run-50). The last assistant deltas of the reply reach the timeline AFTER the
 * turn_finished-derived completion has settled the live turn. Before the fix, that tail opened a
 * fresh live fence nothing would ever settle, so every turn-end repair was refused as NoProgress
 * ("Canonical sweep repair unavailable: NoProgress"), the user's echo never confirmed, and its
 * pending row kept the composer on Thinking until the process died.
 */
class CanonicalLateTailSettlementTest {
    private val scope = TimelineScope("backend", "local-conv-496", "agent")
    private val sentAt = timelineNow().toString()
    private val otid = "cm-android-7e148aa8"

    private fun reply(content: String) = AssistantMessage(
        id = "ui-msg-9173252", contentRaw = JsonPrimitive(content), date = sentAt, runId = "local-run-50",
    )

    // The 0.32.17 message list: seq_id and run_id null, no otid on the assistant row, the user
    // row carries the client message id as its otid.
    private val serverRows = listOf(
        record(UserMessage(id = "message-user", contentRaw = JsonPrimitive("hi"), date = sentAt, otid = otid)),
        record(AssistantMessage(id = "message-reply", contentRaw = JsonPrimitive("Hello there"), date = sentAt)),
    )

    private fun record(message: LettaMessage) = TimelineRemoteRecord(TimelineMessageId(message.id), message, 0)

    private class Harness(
        val coordinator: CanonicalTimelineCoordinator,
        val external: CanonicalExternalTransportWriter,
        val failures: MutableList<Throwable>,
    )

    private fun TestScope.harness(store: InMemoryTimelineStore): Harness {
        val coordinator = CanonicalTimelineCoordinator(store, StaticPageTransport { serverRows })
        val failures = mutableListOf<Throwable>()
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, _, _ -> }, { failures += it })
        val external = CanonicalExternalTransportWriter(coordinator, { _, _ -> scope }, maintenance, now = { sentAt })
        return Harness(coordinator, external, failures)
    }

    /** Send, stream, complete on turn_finished, then deliver the reply's tail behind the terminal. */
    private suspend fun Harness.playPhoneTurn() {
        val agent = scope.agentId
        val conversation = scope.conversationId
        external.appendExternalTransportLocal(agent, conversation, "hi", otid, emptyList())
        external.markExternalTransportLocalSent(agent, conversation, otid)
        external.turnStarted(agent, conversation, "iroh-run-960bd25f", "iroh-turn-c2ad1530")
        external.ingestExternalTransportMessage(
            agent, conversation,
            UserMessage(id = "cm-user-$otid", contentRaw = JsonPrimitive("hi"), date = sentAt, otid = otid),
        )
        external.ingestExternalTransportMessage(agent, conversation, reply("Hel"))
        external.ingestExternalTransportMessage(agent, conversation, reply("lo"))
        // stop_reason, usage and RunLifecycleChanged(Completed) travel the runtime batcher, not the
        // timeline writer; the coordinator's finishActiveTurn then ends the timeline turn.
        external.turnEnded(agent, conversation, clean = true)
        external.clearExternalTransportActive(agent, conversation)
        // The tail the transport emitted behind turn_finished.
        external.ingestExternalTransportMessage(agent, conversation, reply(" there"))
    }

    @Test
    fun lateReplyTailDoesNotReopenTheSettledTurnWhileTheScreenIsOpen() = runTest {
        val store = InMemoryTimelineStore()
        val harness = harness(store)
        val owner = harness.coordinator.acquire(scope)
        val screen = assertNotNull(harness.coordinator.attach(owner))

        harness.playPhoneTurn()

        val settled = assertNotNull(owner.session.live.value)
        assertNotNull(settled.settlementRevision, "the observed terminal must settle the live turn")
        assertEquals(settled.fence, owner.liveFence)

        advanceUntilIdle()

        assertEquals(emptyList(), harness.failures, "turn-end repair must run, not be refused")
        assertEquals(emptyList(), owner.session.pending.value, "the user echo must confirm the local row")
        assertEquals(
            TimelineEnginePageOutcome.Applied,
            harness.coordinator.reconcileRecent(owner),
        )
        assertTrue(harness.coordinator.acknowledgeSettlement(
            owner, settled.fence,
            mapOf(TimelineMessageId("message-user") to 1L, TimelineMessageId("message-reply") to 1L),
        ))
        harness.coordinator.detach(screen)
    }

    @Test
    fun lateReplyTailDoesNotReopenTheSettledTurnWithNoScreenAttached() = runTest {
        val store = InMemoryTimelineStore()
        val harness = harness(store)
        val owner = harness.coordinator.acquire(scope)

        harness.playPhoneTurn()

        // No viewport: the settled overlay was released, and the tail must not open a new turn.
        assertEquals(null, owner.liveFence)
        assertEquals(null, owner.session.live.value)

        advanceUntilIdle()

        assertEquals(emptyList(), harness.failures, "turn-end repair must run, not be refused")
        assertEquals(emptyList(), owner.session.pending.value, "the user echo must confirm the local row")
        assertEquals(TimelineEnginePageOutcome.Applied, harness.coordinator.reconcileRecent(owner))
        assertTrue(harness.coordinator.retire(owner))
    }

    @Test
    fun nextRunAfterSettlementStillOpensItsOwnTurn() = runTest {
        val store = InMemoryTimelineStore()
        val harness = harness(store)
        val owner = harness.coordinator.acquire(scope)
        val screen = assertNotNull(harness.coordinator.attach(owner))

        harness.playPhoneTurn()
        val next = AssistantMessage(
            id = "ui-msg-9173300", contentRaw = JsonPrimitive("again"), date = sentAt, runId = "local-run-51",
        )
        harness.external.ingestExternalTransportMessage(scope.agentId, scope.conversationId, next)

        val live = assertNotNull(owner.session.live.value)
        assertEquals(null, live.settlementRevision)
        assertEquals(listOf("ui-msg-9173300"), live.block.events.map { it.serverId })
        harness.external.turnEnded(scope.agentId, scope.conversationId, clean = true)
        advanceUntilIdle()
        assertEquals(emptyList(), harness.failures)
        harness.coordinator.detach(screen)
    }
}

/** Serves the same recent page to every reconcile. */
private class StaticPageTransport(private val records: () -> List<TimelineRemoteRecord>) : TimelineTransport {
    override suspend fun listConversationMessagePage(
        request: TimelineRemotePageRequest,
        progress: TimelinePageProgress?,
    ): TimelineRemotePageResult =
        TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, records(), null, false, 0)

    override suspend fun sendConversationMessage(
        conversationId: String, request: com.letta.mobile.data.model.MessageCreateRequest,
    ): kotlinx.coroutines.flow.Flow<LettaMessage> = error("unexpected send")

    override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> =
        error("unexpected stream")

    override suspend fun listConversationMessages(
        conversationId: String, limit: Int?, after: String?, order: String?,
    ): List<LettaMessage> = error("legacy hydration")

    override suspend fun listAgentMessages(
        agentId: String, limit: Int?, order: String?, conversationId: String?,
    ): List<LettaMessage> = error("legacy hydration")
}
