package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.StopReason
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The turn capture7 recorded on the Pixel (letta-mobile-qygvv.24): the phone's echo of a sent
 * prompt, keyed `msg-cm-android-<otid>`, and a `local-run-N` run of reasoning and a reply; then the
 * durable page the reconcile reads back, which names neither the echo nor the local run.
 */
internal object OverlapTurn {
    const val OTID = "cm-android-a3789bb3"
    const val LIVE_RUN = "local-run-65"
    val scope = TimelineScope("backend", "local-conv-512", "agent")

    private val prompt = UserMessage(
        id = "ui-msg-9180001", contentRaw = JsonPrimitive("try again"),
        date = "2026-09-25T15:47:16.900Z", otid = OTID,
    )
    private val echo = prompt.copy(id = "cm-user-$OTID", date = "2026-09-25T15:47:17.867Z")
    private val thought = ReasoningMessage(id = "ui-msg-9180002", reasoning = "Retry it", date = "2026-09-25T15:47:18.100Z")
    val reply = AssistantMessage(id = "ui-msg-9180003", contentRaw = JsonPrimitive("Done."), date = "2026-09-25T15:47:23.000Z")
    private val stop = StopReason(id = "stop-65", reason = "end_turn", date = reply.date)
    private val usage = UsageStatistics(id = "usage-65", promptTokens = 1_024, completionTokens = 3, date = reply.date)

    val live: List<LettaMessage> = listOf(echo, thought.copy(runId = LIVE_RUN), reply.copy(runId = LIVE_RUN))
    val durable: List<LettaMessage> = listOf(usage, stop, reply, thought, prompt)
}

/** What one frame of the Android list shows: the raw live overlay and one settled snapshot. */
internal data class OverlapFrame(val live: List<ChatRenderItem>, val settledKeys: List<String?>) {
    val rows: TimelineRowAssembly get() = TimelineRowAssembly.assemble(live, settledKeys)
    val keys: List<String?> get() = rows.let { assembled -> (0 until assembled.size).map(assembled::key) }
}

/** Guard drops recorded for [keys]; Telemetry is process-wide, so other tests' keys are ignored. */
internal fun guardDrops(keys: Set<String>): Int = Telemetry.snapshot().count { event ->
    event.name == "timeline.duplicateKeyDropped" && event.attrs["key"] in keys
}

/** One conversation's coordinator and presentation, with a presenter standing in for Paging. */
internal class OverlapHarness private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val owner: CanonicalTimelineCoordinator.Owner,
    private val ui: CoroutineScope,
    private val presentation: CanonicalTimelinePresentation,
) {
    companion object {
        suspend fun open(durable: List<LettaMessage>): OverlapHarness {
            val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), OverlapTransport(durable))
            val owner = coordinator.acquire(OverlapTurn.scope)
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return OverlapHarness(coordinator, owner, ui, CanonicalTimelinePresentation.open(coordinator, owner, ui))
        }
    }

    private val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()

    fun frame(): OverlapFrame = OverlapFrame(presentation.live.value, presenter.snapshot().map { it?.item?.key })

    suspend fun streamTurn(frames: List<LettaMessage>) {
        val fence = coordinator.beginLive(owner)
        frames.forEach { assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(it))) }
        assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        awaitCondition({ "live turn never projected: ${presentation.live.value}" }) {
            presentation.live.value.size == 2 && presentation.live.value.first().containsMessageId(OverlapTurn.reply.id)
        }
    }

    /** The durable page lands; the overlay has not seen it resident yet, so it still holds the turn. */
    suspend fun reconcile() {
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        presenter.awaitRows(2) { "settled turn never arrived" }
        presenter.awaitIdle()
    }

    /** The list reports the rows resident, which acknowledges settlement and drains the overlay. */
    suspend fun drainOverlay() {
        presentation.onResidentRows(presenter.snapshot().items)
        awaitCondition({ "live overlay did not drain: ${presentation.live.value}" }) {
            owner.session.live.value == null && presentation.live.value.isEmpty()
        }
    }

    suspend fun close() {
        presentation.close()
        ui.cancel()
    }

    private class OverlapTransport(private val durable: List<LettaMessage>) :
        TimelineTransport by unexpectedTimelineTransport() {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )
    }
}
