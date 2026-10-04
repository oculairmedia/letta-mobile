package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One conversation's real coordinator and presentation, with a presenter recording its settled
 * rows: a turn can be streamed live as the device saw it, then reconciled from the durable page
 * App Server `message.list` returns, and both renders compared.
 */
internal class CanonicalTurnHarness private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val owner: CanonicalTimelineCoordinator.Owner,
    private val ui: CoroutineScope,
    private val presentation: CanonicalTimelinePresentation,
) {
    companion object {
        suspend fun open(scope: TimelineScope, durable: List<LettaMessage>): CanonicalTurnHarness {
            val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), DurableTransport(durable))
            val owner = coordinator.acquire(scope)
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return CanonicalTurnHarness(coordinator, owner, ui, CanonicalTimelinePresentation.open(coordinator, owner, ui))
        }
    }

    private val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
    private val generations = AtomicInteger()

    /**
     * Streams [frames] as the device saw them, then Done when [finished] (a turn still running sends
     * none), and returns the live render once [projected] holds for it.
     */
    suspend fun stream(
        frames: List<LettaMessage>,
        finished: Boolean,
        projected: (List<ChatRenderItem>) -> Boolean,
    ): List<ChatRenderItem> {
        val fence = coordinator.beginLive(owner)
        frames.forEach { assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(hostStamped(it)))) }
        if (finished) assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        // The presentation projects on its own dispatcher: wait for the final frame, not the first.
        awaitCondition({ "live turn never projected: ${presentation.live.value.map { it.key }}" }) {
            projected(presentation.live.value)
        }
        return presentation.live.value
    }

    suspend fun settle() {
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        ui.launch {
            presentation.settled.collectLatest { generations.incrementAndGet(); presenter.collectFrom(it) }
        }
        presenter.awaitRows(2) { "settled turn never arrived" }
        presenter.awaitIdle()
    }

    suspend fun drainAndRepage() {
        presentation.onResidentRows(presenter.snapshot().items)
        awaitCondition({ "live overlay did not drain" }) { owner.session.live.value == null }
        val before = generations.get()
        owner.session.engine.advanceToolSweep(owner.selection)
        awaitCondition({ "no paging generation after the revision" }) { generations.get() > before }
        presenter.awaitIdle()
    }

    fun rows(): List<ChatRenderItem> = presenter.snapshot().items.map { it.item }

    suspend fun close() {
        presentation.close()
        ui.cancel()
    }

    /** The recent page App Server `message.list` returns for the turn. */
    private class DurableTransport(private val durable: List<LettaMessage>) :
        TimelineTransport by unexpectedTimelineTransport() {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )
    }
}
