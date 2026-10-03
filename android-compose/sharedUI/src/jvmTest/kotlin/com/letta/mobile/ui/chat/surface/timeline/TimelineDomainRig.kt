package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineEnginePageOutcome
import com.letta.mobile.data.timeline.TimelineLiveFence
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
    /** The App Server `message.list` recent page, swapped per test step. Every other call fails the test. */
    class MutableDurableTransport : TimelineTransport by unexpectedTransport() {
        @Volatile var durable: List<LettaMessage> = emptyList()

        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )
    }

    /** Opens on history the ledger already holds. */
    suspend fun openOn(durable: List<LettaMessage>) {
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
    }

    /** A prompt the user just sent, before the server has seen it: [echo] carries its otid, text and time. */
    suspend fun send(echo: UserMessage) {
        coordinator.appendPending(owner, CanonicalPendingLocalStore.Record(echo.otid.orEmpty(), echo.content, emptyList(), echo.date.orEmpty()))
    }

    /** Begins a turn's live stream. */
    suspend fun beginStream(): Stream = Stream(coordinator.beginLive(owner))

    inner class Stream internal constructor(private val fence: TimelineLiveFence) {
        suspend fun emit(message: LettaMessage) {
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(message)))
        }

        suspend fun done() {
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        }
    }

    /**
     * The durable turn lands: the ledger reconciles and Paging re-presents. The overlay drains once
     * the list reports the new rows resident, which takes Compose frames; see [drained].
     */
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
            val coordinator = CanonicalTimelineCoordinator(UiFrameTimelineStore(READ_LATENCY_MILLIS), transport)
            val owner = coordinator.acquire(scope)
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
            return TimelineDomainRig(coordinator, owner, ui, presentation, transport)
        }

        /** A transport whose every call fails the test; subclasses override only what a step expects. */
        private fun unexpectedTransport(): TimelineTransport = java.lang.reflect.Proxy.newProxyInstance(
            TimelineTransport::class.java.classLoader,
            arrayOf(TimelineTransport::class.java),
        ) { _, method, _ -> fail("unexpected transport call: ${method.name}") } as TimelineTransport
    }
}
