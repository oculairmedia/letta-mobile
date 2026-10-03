package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineEnginePageOutcome
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelinePageProgress
import com.letta.mobile.data.timeline.TimelineRemotePageRequest
import com.letta.mobile.data.timeline.TimelineRemotePageResult
import com.letta.mobile.data.timeline.TimelineRemoteRecord
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-29sxj: one conversation's real coordinator and presentation, driven through the
 * turn a user sees: history, the optimistic send, the stream and the durable page that settles it.
 *
 * The wiring in [open] is the same as sharedLogic's TimelineFrameRecorder.open (a jvmTest class of
 * another module, so not reachable from here): CanonicalTimelinePresentation.open over an
 * in-memory store. Unlike that recorder this rig records nothing; the Compose list under test
 * reads [presentation] itself, which is the point of the harness.
 */
internal class TimelineDomainRig private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val owner: CanonicalTimelineCoordinator.Owner,
    private val ui: CoroutineScope,
    val presentation: CanonicalTimelinePresentation,
    private val transport: MutableDurableTransport,
) {
    /** The App Server `message.list` recent page, swapped per test step. */
    class MutableDurableTransport : TimelineTransport {
        @Volatile var durable: List<LettaMessage> = emptyList()

        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )

        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> =
            error("the rig never sends through the transport")

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
            error("the rig feeds the stream itself")

        override suspend fun listConversationMessages(
            conversationId: String, limit: Int?, after: String?, order: String?,
        ): List<LettaMessage> = error("the rig pages through listConversationMessagePage")

        override suspend fun listAgentMessages(
            agentId: String, limit: Int?, order: String?, conversationId: String?,
        ): List<LettaMessage> = error("the rig never lists an agent")
    }

    /** Opens on history the ledger already holds. */
    suspend fun openOn(durable: List<LettaMessage>) {
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
    }

    /** A prompt the user just sent, before the server has seen it. */
    suspend fun send(otid: String, content: String, sentAt: String) {
        coordinator.appendPending(owner, CanonicalPendingLocalStore.Record(otid, content, emptyList(), sentAt))
    }

    /** Begins a turn's live stream. */
    suspend fun beginStream(): Stream = Stream(coordinator.beginLive(owner))

    inner class Stream internal constructor(private val fence: com.letta.mobile.data.timeline.TimelineLiveFence) {
        suspend fun emit(message: LettaMessage) {
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(message)))
        }

        suspend fun done() {
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        }
    }

    /** The durable turn lands: the ledger reconciles, Paging re-presents, the overlay drains. */
    suspend fun settle(durable: List<LettaMessage>) {
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
    }

    /** True once the live overlay has handed everything to the settled ledger. */
    val drained: Boolean get() = owner.session.live.value == null && presentation.live.value.isEmpty()

    suspend fun close() {
        presentation.close()
        ui.cancel()
    }

    companion object {
        private const val READ_LATENCY_MILLIS = 25L

        suspend fun open(scope: TimelineScope): TimelineDomainRig {
            val transport = MutableDurableTransport()
            val coordinator = CanonicalTimelineCoordinator(UiFrameTimelineStore(readLatencyMillis = READ_LATENCY_MILLIS), transport)
            val owner = coordinator.acquire(scope)
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
            return TimelineDomainRig(coordinator, owner, ui, presentation, transport)
        }
    }
}
