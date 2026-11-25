package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.session.CanonicalTimelineChatSession
import com.letta.mobile.data.timeline.session.CanonicalTimelineSend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The canonical chat session must be the presentation drawn as a [Timeline]: one conversation, one
 * coordinator, one writer, and two surfaces (the paged presentation and the shared chat owner's
 * session) reading the same underlying rows. Every assertion holds the session to an oracle that is
 * independent of it: the fixture messages the durable page and the live stream actually carried, the
 * presentation rendered through the real coordinator, and the coordinator's own pending store. A
 * session that projected its own copy of the conversation, or wrote by a path of its own, cannot
 * pass these.
 */
class CanonicalTimelineChatSessionTest {
    @Test fun settledTailPageIsExactlyThePresentationsRows() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = settledHistory)
        val surfaces = harness.openSurfaces()
        try {
            surfaces.session.hydrate()
            harness.settle()

            val timeline = surfaces.session.awaitTimeline { it.events.size == DURABLE_ROWS }

            // The fixture oracle: the durable page the transport served, in ledger order.
            assertEquals(listOf(PROMPT_TEXT, FIRST_REPLY_TEXT), timeline.events.confirmedContents(),
                "the settled tail must be exactly the durable fixture, in ledger order")
            assertEquals(PROMPT_OTID, timeline.events.confirmed().first().otid,
                "a stored user prompt keeps the client message id as its identity")

            // The presentation oracle: the session draws the same rows, message for message.
            assertEquals(harness.renderedRows().serverIds(),
                timeline.events.confirmed().map { it.serverId },
                "the session and the presentation must name the same settled rows")
            assertEquals(harness.renderedRows().sentences(),
                timeline.events.confirmedContents(),
                "the session and the presentation must draw the same rendered texts")
        } finally {
            surfaces.close()
            harness.close()
        }
    }

    @Test fun aStreamedReplyIsLiveOnceThenSettledOnce() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = settledHistory + newestReply())
        val surfaces = harness.openSurfaces()
        try {
            // The turn streams before anything read the ledger, as a device saw it; Done settles
            // the overlay without moving any row, until the durable catch-up replaces them.
            harness.stream(listOf(streamedReply()), finished = true) { rows ->
                rows.any { row -> row.drawnText(SECOND_REPLY_TEXT) }
            }
            val streaming = surfaces.session.awaitTimeline { timeline ->
                timeline.events.confirmedContents().contains(SECOND_REPLY_TEXT)
            }
            assertEquals(listOf(SECOND_REPLY_TEXT), streaming.events.confirmedContents(),
                "while the turn streams the session draws the live overlay, not a copy of it")

            // The durable catch-up takes the live row over; nothing may be drawn twice.
            harness.settle(expectedRows = settledHistory.size + NEWEST_ROWS)
            val settled = surfaces.session.awaitTimeline { timeline ->
                timeline.events.confirmedContents().size == settledHistory.size + NEWEST_ROWS &&
                    timeline.events.confirmedContents().count { it == SECOND_REPLY_TEXT } == 1
            }
            assertEquals(listOf(PROMPT_TEXT, FIRST_REPLY_TEXT, SECOND_REPLY_TEXT),
                settled.events.confirmedContents(),
                "settling must hand the streamed turn to its durable rows exactly once")
            assertEquals(harness.renderedRows().sentences(), settled.events.confirmedContents(),
                "after the catch-up the session and the presentation still draw the same rows")
        } finally {
            surfaces.close()
            harness.close()
        }
    }

    @Test fun hydrateRunsTheCanonicalFirstPageLoadIdempotently() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = settledHistory)
        val surfaces = harness.openSurfaces()
        try {
            val relayed = CopyOnWriteArrayList<TimelineSyncEvent>()
            surfaces.scope.launch { surfaces.session.events.collect { relayed += it } }
            val openSelection = harness.owner.session.publication.value.selection

            surfaces.session.hydrate()
            val first = surfaces.session.awaitTimeline { it.events.size == DURABLE_ROWS }
            assertEquals(listOf(PROMPT_TEXT, FIRST_REPLY_TEXT), first.events.confirmedContents(),
                "hydrate must load the conversation's recent durable tail")

            surfaces.session.hydrate()
            val second = surfaces.session.awaitTimeline { it.events.size == DURABLE_ROWS }
            assertEquals(first.events.confirmedContents(), second.events.confirmedContents(),
                "a second hydrate must not duplicate or reorder the loaded tail")
            assertEquals(openSelection, harness.owner.session.publication.value.selection,
                "hydration is a page load; it must never reopen the conversation's runtime")
            assertTrue(relayed.any { it is TimelineSyncEvent.Hydrated },
                "the session must relay its load on the shared event contract")
        } finally {
            surfaces.close()
            harness.close()
        }
    }

    @Test fun sendProducesOneOptimisticRowThroughThePendingSendFamily() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = emptyList())
        val writer = CountingWriter(harness.externalWriter())
        val dispatched = CopyOnWriteArrayList<Pair<String, String>>()
        val transport = CanonicalTimelineSend { otid, message -> dispatched += otid to message.text }
        val surfaces = harness.openSurfaces(writer = writer, transport = transport)
        try {
            surfaces.session.hydrate()
            surfaces.session.send(USER_PROMPT_TEXT, emptyList())

            assertEquals(listOf(USER_PROMPT_TEXT), dispatched.map { it.second },
                "the session hands its one prompt to the host transport")
            assertEquals(1, writer.appendedPrompts,
                "a send must write exactly one optimistic row through the writer's pending-send family")
            assertEquals(0, writer.markedPrompts, "a delivered send must not mark any prompt")

            val record = harness.owner.session.pending.value.single()
            assertEquals(USER_PROMPT_TEXT, record.content,
                "the writer's pending store is the single optimistic row")
            assertEquals(dispatched.single().first, record.otid,
                "the transport and the pending store share one identity")

            val timeline = surfaces.session.awaitTimeline { it.events.isNotEmpty() }
            val local = timeline.events.locals().single()
            assertEquals(record.otid, local.otid, "the session projects the same optimistic row it wrote")
            assertEquals(record.otid, surfaces.session.lastMintedOtid,
                "the session's minted identity must survive to the optimistic row and the transport")

            awaitCondition({ "the presentation never drew the optimistic row" }) {
                harness.liveOverlay().any { row -> row.drawnText(USER_PROMPT_TEXT) }
            }
            assertEquals(USER_PROMPT_TEXT, timeline.events.locals().single().content,
                "the optimistic prompt must be one row in both surfaces")
        } finally {
            surfaces.close()
            harness.close()
        }
    }

    @Test fun aFailedDispatchMarksThePromptFailedAndRelaysTheError() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = emptyList())
        val writer = CountingWriter(harness.externalWriter())
        val transport = CanonicalTimelineSend { _, _ ->
            throw IllegalStateException("the host transport refused the send")
        }
        val surfaces = harness.openSurfaces(writer = writer, transport = transport)
        try {
            surfaces.session.hydrate()
            val relayed = CopyOnWriteArrayList<TimelineSyncEvent>()
            surfaces.scope.launch { surfaces.session.events.collect { relayed += it } }

            surfaces.session.send(USER_PROMPT_TEXT, emptyList())

            awaitCondition({ "the failed prompt never reached the pending store" }) {
                harness.owner.session.pending.value.any { it.delivery == CanonicalPendingLocalStore.Delivery.Failed }
            }
            assertEquals(1, writer.appendedPrompts,
                "the failed send still wrote its one optimistic row")
            assertEquals(1, writer.markedPrompts,
                "the session hands the failure to the writer's family, never to a path of its own")

            // The store says Failed before the session has projected it: wait for the session's own
            // row to read FAILED rather than for any frame, which can still be the SENDING one.
            val timeline = surfaces.session.awaitTimeline {
                it.events.locals().singleOrNull()?.deliveryState == DeliveryState.FAILED
            }
            assertEquals(DeliveryState.FAILED, timeline.events.locals().single().deliveryState,
                "the optimistic row must read as failed in the session, as in the writer's store")
            assertTrue(relayed.filterIsInstance<TimelineSyncEvent.StreamError>().isNotEmpty(),
                "the session must relay the transport failure on the shared event contract")
        } finally {
            surfaces.close()
            harness.close()
        }
    }

    @Test fun closingStopsTheSessionAndFreezesItsTimeline() = runBlocking {
        val harness = CanonicalTurnHarness.open(scope, durable = settledHistory)
        val surfaces = harness.openSurfaces()
        try {
            surfaces.session.hydrate()
            surfaces.session.awaitTimeline { it.events.size == DURABLE_ROWS }

            harness.stream(listOf(streamedReply()), finished = false) { rows -> rows.isNotEmpty() }
            surfaces.session.awaitTimeline { it.events.size == DURABLE_ROWS + LIVE_ROWS }
            surfaces.session.close()

            assertFailsWith<IllegalStateException>("a closed session must refuse to hydrate again") {
                surfaces.session.hydrate()
            }
            assertFailsWith<IllegalStateException>("a closed session must refuse to send") {
                surfaces.session.send(USER_PROMPT_TEXT, emptyList())
            }

            harness.stream(listOf(streamedFollowUp()), finished = false) { rows ->
                rows.any { row -> row.drawnText(FOLLOW_UP_TEXT) }
            }
            delay(FROZEN_PROBE_MILLIS)
            assertEquals(listOf(PROMPT_TEXT, FIRST_REPLY_TEXT, SECOND_REPLY_TEXT),
                surfaces.session.timeline.value.events.confirmedContents(),
                "a closed session must stop projecting the conversation (cancellation is its cleanup)")
        } finally {
            surfaces.cancelScope()
            harness.close()
        }
    }

    /** The session and the scope it runs in, beside the harness's real coordinator and writer. */
    private class SessionSurfaces(
        val scope: CoroutineScope,
        val session: CanonicalTimelineChatSession,
    ) {
        suspend fun close() {
            session.close()
            cancelScope()
        }

        fun cancelScope() = scope.cancel()
    }

    private fun CanonicalTurnHarness.openSurfaces(
        writer: TimelineExternalTransportWriter = externalWriter(),
        transport: CanonicalTimelineSend = CanonicalTimelineSend { _, _ -> },
    ): SessionSurfaces {
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = CanonicalTimelineChatSession(coordinator, owner, writer, transport, sessionScope)
        // The shared owner keeps its session subscribed the whole time it is open.
        sessionScope.launch { session.timeline.collect {} }
        return SessionSurfaces(sessionScope, session)
    }

    private fun CanonicalTurnHarness.externalWriter(): TimelineExternalTransportWriter =
        CanonicalExternalTransportWriter(
            coordinator,
            resolveScope = { _, _ -> scope },
            maintenance = IndexedCanonicalTimelineMaintenance(
                coordinator,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                repairCommittedCursor = { _, _, _ -> },
                reportFailure = {},
            ),
        )

    /** The real writer family, with the two pending-send calls the session makes counted. */
    private class CountingWriter(
        private val delegate: TimelineExternalTransportWriter,
    ) : TimelineExternalTransportWriter by delegate {
        var appendedPrompts = 0
        var markedPrompts = 0

        override suspend fun appendExternalTransportLocal(
            agentId: String?,
            conversationId: String,
            content: String,
            otid: String,
            attachments: List<MessageContentPart.Image>,
        ): String {
            appendedPrompts++
            return delegate.appendExternalTransportLocal(agentId, conversationId, content, otid, attachments)
        }

        override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) {
            markedPrompts++
            delegate.markExternalTransportLocalFailed(agentId, conversationId, otid)
        }
    }

    private suspend fun CanonicalTimelineChatSession.awaitTimeline(
        holding: (Timeline) -> Boolean,
    ): Timeline {
        awaitCondition({ "the session timeline never reached the awaited state: ${timeline.value.events.confirmedContents()}" }) {
            holding(timeline.value)
        }
        return timeline.value
    }

    private fun List<TimelineEvent>.confirmed(): List<TimelineEvent.Confirmed> =
        filterIsInstance<TimelineEvent.Confirmed>()

    private fun List<TimelineEvent>.locals(): List<TimelineEvent.Local> =
        filterIsInstance<TimelineEvent.Local>()

    private fun List<TimelineEvent>.confirmedContents(): List<String> =
        confirmed().map { it.content }

    /**
     * The presentation's rendered rows in chat reading order (oldest first, the [Timeline]'s own
     * order), as lists a session projection can be compared against row for row.
     */
    private data class RenderedRows(val rows: List<ChatRenderItem>) {
        fun serverIds() = messages().map { it.id }
        fun sentences() = messages().map { it.content }
        private fun messages(): List<UiMessage> = rows.asReversed().flatMap { it.messagesInOrder() }
    }

    private fun CanonicalTurnHarness.renderedRows() = RenderedRows(rows())

    private fun ChatRenderItem.drawnText(text: String): Boolean =
        messagesInOrder().any { it.content == text }

    private fun ChatRenderItem.messagesInOrder(): List<UiMessage> = when (this) {
        is ChatRenderItem.Single -> listOf(message)
        is ChatRenderItem.RunBlock -> messages.map { it.first }
    }

    private fun streamedReply() = hostStamped(
        AssistantMessage(id = REPLY_TWO_ID, contentRaw = JsonPrimitive(SECOND_REPLY_TEXT), date = at(3), runId = SECOND_RUN),
    )

    private fun streamedFollowUp() = hostStamped(
        AssistantMessage(id = FOLLOW_UP_ID, contentRaw = JsonPrimitive(FOLLOW_UP_TEXT), date = at(4), runId = SECOND_RUN),
    )

    private companion object {
        const val PROMPT_TEXT = "where is my order?"
        const val FIRST_REPLY_TEXT = "It shipped this morning."
        const val SECOND_REPLY_TEXT = "It arrives today."
        const val FOLLOW_UP_TEXT = "The courier is around the corner."
        const val USER_PROMPT_TEXT = "did my package arrive today?"
        const val PROMPT_OTID = "cm-order-1"
        const val REPLY_TWO_ID = "msg-reply-2"
        const val FOLLOW_UP_ID = "msg-follow-up"
        const val FIRST_RUN = "local-run-10"
        const val SECOND_RUN = "local-run-11"
        const val DURABLE_ROWS = 2
        const val NEWEST_ROWS = 1
        const val LIVE_ROWS = 1
        const val FROZEN_PROBE_MILLIS = 250L

        val scope = TimelineScope("backend", "chat-707", "agent")

        fun at(second: Int) = "2026-09-25T08:15:%02d.000Z".format(second)

        val settledHistory = listOf(
            UserMessage(id = "msg-answer", contentRaw = JsonPrimitive(PROMPT_TEXT), date = at(0), otid = PROMPT_OTID),
            AssistantMessage(id = "msg-reply", contentRaw = JsonPrimitive(FIRST_REPLY_TEXT), date = at(1), runId = FIRST_RUN),
        )

        fun newestReply() = AssistantMessage(
            id = REPLY_TWO_ID, logicalMessageId = REPLY_TWO_ID,
            contentRaw = JsonPrimitive(SECOND_REPLY_TEXT), date = at(2), runId = SECOND_RUN,
        )
    }
}
