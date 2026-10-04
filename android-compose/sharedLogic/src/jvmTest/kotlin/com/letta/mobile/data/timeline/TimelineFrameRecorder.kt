package com.letta.mobile.data.timeline

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.12: one conversation's real coordinator and presentation, presented the way
 * the shared paged timeline presents it (PagedTimelineList): the live overlay and a Paging
 * presenter's snapshot are read together into a [TimelineRowAssembly], and every settled snapshot is
 * reported resident (ObserveResidentRows), which is what drains the overlay.
 *
 * Every change to either source records a [Frame]. Each is a state the list can draw, because a
 * Compose frame reads whatever both sources hold at that moment; so a key that leaves a frame and
 * comes back, a message whose row changes key between two frames, or one message drawn in two rows
 * of one frame, is a flicker the device can show, not just a theory about one.
 */
internal class TimelineFrameRecorder private constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val owner: CanonicalTimelineCoordinator.Owner,
    private val ui: CoroutineScope,
    private val presentation: CanonicalTimelinePresentation,
    private val transport: MutableDurableTransport,
    private val scope: TimelineScope,
) {
    /** One row as the list keys and draws it. */
    data class FrameRow(val key: String, val source: String, val messages: List<String>, val contents: List<String>)

    /**
     * [loadStates] are the Paging refresh/append states the presenter reported since the previous
     * recorded frame (for example `refresh:Loading`), the signal behind a spinner flash.
     */
    data class Frame(val index: Int, val step: String, val rows: List<FrameRow>, val loadStates: List<String> = emptyList()) {
        val keys: List<String> get() = rows.map { it.key }
        override fun toString() = "#$index [$step] " + rows.joinToString { "${it.key}<${it.source}>${it.messages}" }
    }

    private val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()
    private val lock = Any()
    private val recorded = mutableListOf<Frame>()
    private val pendingLoadStates = mutableListOf<String>()
    private var step = "open"

    val frames: List<Frame> get() = synchronized(lock) { recorded.toList() }

    private fun start() {
        ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
        ui.launch {
            presenter.onPagesUpdatedFlow.collect {
                // As ObserveResidentRows: every snapshot the list holds is reported resident.
                presentation.onResidentRows(presenter.snapshot().items)
                record()
            }
        }
        ui.launch { presentation.live.collect { record() } }
        ui.launch { presenter.loadStateFlow.filterNotNull().collect(::noteLoadState) }
    }

    private fun noteLoadState(states: CombinedLoadStates) = synchronized(lock) {
        val named = "refresh:${states.refresh.name()} append:${states.append.name()}"
        if (pendingLoadStates.lastOrNull() != named) pendingLoadStates += named
    }

    private fun LoadState.name(): String = this::class.simpleName.orEmpty()

    private fun record() = synchronized(lock) {
        val live = presentation.live.value
        val snapshot = presenter.snapshot()
        val assembly = TimelineRowAssembly.assemble(live, snapshot.map { it?.item?.key })
        val rows = (0 until assembly.size).map { index ->
            val item = assembly.live.getOrNull(index) ?: snapshot[index - assembly.liveCount]?.item
            val source = if (index < assembly.liveCount) TimelineRowAssembly.LIVE else TimelineRowAssembly.SETTLED
            FrameRow(
                key = assembly.key(index) ?: "placeholder-$index",
                source = source,
                messages = item?.messageRows().orEmpty().map { it.logicalId() },
                contents = item?.messageRows().orEmpty().map { it.content },
            )
        }
        if (recorded.lastOrNull()?.rows == rows) return@synchronized
        recorded += Frame(recorded.size, step, rows, pendingLoadStates.toList())
        pendingLoadStates.clear()
    }

    /** A prompt the user just sent, before the server has seen it. */
    suspend fun send(otid: String, content: String, sentAt: String) {
        step = "send"
        coordinator.appendPending(owner, CanonicalPendingLocalStore.Record(otid, content, emptyList(), sentAt))
        awaitCondition({ "optimistic prompt never projected: ${frames.lastOrNull()}" }) {
            frames.lastOrNull()?.rows?.any { otid in it.messages } == true
        }
    }

    /** Streams [frames] one at a time, letting each one reach the overlay before the next. */
    suspend fun stream(frames: List<LettaMessage>, finished: Boolean = true) {
        val fence = coordinator.beginLive(owner)
        frames.forEach { message ->
            step = "stream ${message.id}"
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(message)))
            settleUi()
        }
        if (finished) {
            step = "done"
            assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
            settleUi()
        }
    }

    /**
     * Streams raw `stream_delta` wire lines (as recorded on a host or viewer) through the observer
     * mapping the phone uses, so a captured turn replays without being rewritten as typed messages.
     */
    suspend fun streamRaw(frames: List<String>, finished: Boolean = false) =
        stream(ObserverFrameReplay(scope).messages(frames), finished)

    /** The durable turn lands: the ledger reconciles, Paging re-presents, the overlay drains. */
    suspend fun settle(durable: List<LettaMessage>) {
        step = "settle"
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        awaitCondition({ "overlay never drained: ${frames.lastOrNull()}" }) {
            owner.session.live.value == null && presentation.live.value.isEmpty()
        }
        // Not awaitIdle: a prepend racing the reconcile may end in a stale-request error, which
        // the list shows as nothing and which says nothing about the handover.
        settleUi()
    }

    /** Opens on history the ledger already holds. */
    suspend fun openOn(durable: List<LettaMessage>) {
        step = "history"
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        presenter.awaitRows(durable.count { it.renders() }) { "history never paged in: ${frames.lastOrNull()}" }
        presenter.awaitIdle()
    }

    /** Opens on stored history whose grouping into rows is not known up front (a captured turn). */
    suspend fun openOnHistory(durable: List<LettaMessage>) {
        step = "history"
        transport.durable = durable
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        awaitCondition({ "history never paged in: ${frames.lastOrNull()}" }) { presenter.size > 0 }
        presenter.awaitIdle()
    }

    private suspend fun settleUi() {
        repeat(SETTLE_YIELDS) { yield(); kotlinx.coroutines.delay(1) }
    }

    fun lastFrame(): Frame = frames.last()

    suspend fun close() {
        presentation.close()
        ui.cancel()
    }

    /** The App Server `message.list` recent page, swapped per test step. */
    class MutableDurableTransport : TimelineTransport by unexpectedTimelineTransport() {
        @Volatile var durable: List<LettaMessage> = emptyList()

        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )
    }

    companion object {
        private const val SETTLE_YIELDS = 20

        suspend fun open(scope: TimelineScope): TimelineFrameRecorder {
            val transport = MutableDurableTransport()
            val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), transport)
            val owner = coordinator.acquire(scope)
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val presentation = CanonicalTimelinePresentation.open(coordinator, owner, ui)
            return TimelineFrameRecorder(coordinator, owner, ui, presentation, transport, scope).also { it.start() }
        }

        private fun LettaMessage.renders(): Boolean =
            this !is com.letta.mobile.data.model.StopReason && this !is com.letta.mobile.data.model.UsageStatistics &&
                this !is com.letta.mobile.data.model.ToolReturnMessage
    }
}

internal fun ChatRenderItem.messageRows(): List<UiMessage> = when (this) {
    is ChatRenderItem.RunBlock -> messages.map { it.first }
    is ChatRenderItem.Single -> listOf(message)
}

/**
 * What names one message on both sides of the handover: a prompt by the otid it keeps from its
 * optimistic bubble to its stored row, anything else by its server id.
 */
internal fun UiMessage.logicalId(): String =
    if (role == "user") clientMessageId?.takeIf(String::isNotBlank) ?: id else id.removeSuffix(":REASONING")

/** Every frame-to-frame flicker [frames] show; empty when the sequence is seamless. */
internal fun handoverFlickers(frames: List<TimelineFrameRecorder.Frame>): List<String> =
    frames.flatMap(::drawnTwice) + frames.zipWithNext().flatMap { (before, after) -> movedKeys(before, after) } +
        returningKeys(frames)

private val TimelineFrameRecorder.Frame.label: String get() = "frame $index [$step]"

private fun TimelineFrameRecorder.Frame.keyByMessage(): List<Pair<String, String>> =
    rows.flatMap { row -> row.messages.map { it to row.key } }

/** One message in two rows of one frame. */
private fun drawnTwice(frame: TimelineFrameRecorder.Frame): List<String> =
    frame.keyByMessage().groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        .map { (message, keys) -> "${frame.label}: $message drawn twice under $keys" }

/** A message whose row changed key between two frames. */
private fun movedKeys(before: TimelineFrameRecorder.Frame, after: TimelineFrameRecorder.Frame): List<String> {
    val keyOf = before.keyByMessage().toMap()
    return after.keyByMessage().mapNotNull { (message, key) ->
        keyOf[message]?.takeIf { it != key }?.let { "${after.label}: $message moved from key $it to $key" }
    }
}

/** A key that left the list and later came back: its row was removed and re-inserted. */
private fun returningKeys(frames: List<TimelineFrameRecorder.Frame>): List<String> {
    val gone = mutableMapOf<String, Int>()
    return frames.zipWithNext().flatMap { (before, after) ->
        (before.keys - after.keys.toSet()).forEach { gone[it] = after.index }
        after.keys.mapNotNull { key ->
            gone.remove(key)?.let { left -> "${after.label}: key $key left at frame $left and came back" }
        }
    }
}
