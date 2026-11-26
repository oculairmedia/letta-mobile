package com.letta.mobile.data.feed.client

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.feed.wire.FeedDeltaFrame
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.resolveConversationReadTarget
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds and starts one conversation's feed rows in the scope it is given; null rows until the first read finished. */
typealias FeedRowsFactory = (CoroutineScope) -> StateFlow<List<ChatRenderItem>?>

/** What the feed path did to the list, so the old and the feed path can be compared on a device. */
data class FeedTimelineMetrics(
    /** Times the list was published with a change. */
    val publications: Long = 0,
    /** Publications that were not row updates and appends: rows removed or reordered, or the host replaced the journal. */
    val listReplacements: Long = 0,
    /** Rows whose content changed or that appeared, summed over every publication: the rows Compose redraws. */
    val rowsRedrawn: Long = 0,
    /** `feed.subscribe` reads made (a catch-up page each). */
    val pagesRead: Long = 0,
    /** Resyncs forced by a frame the client could not follow, or by a reconnect. */
    val resyncs: Long = 0,
    /** `feed_delta` frames applied. */
    val framesApplied: Long = 0,
)

/**
 * One conversation's timeline as the host's feed records it: a single ordered list of rows built from `feed.subscribe` pages and
 * `feed_delta` pushes, and nothing else. It holds a cursor, applies a frame only when it continues the cursor and otherwise
 * re-reads from the cursor (never guessing), and applies a cumulative snapshot over its predecessor where it stands, so a row keeps
 * its key and its instance unless its own content changed and the list below it redraws only that row.
 *
 * It is available only against a host that advertises both [ConversationFeedWire.CAPABILITY] and
 * [ConversationFeedWire.PUSH_CAPABILITY] ([isAvailable]); against any other the caller keeps the path it had. [sink] receives
 * every message an applied entry carries, for a consumer that writes them into its own timeline instead of drawing [rows].
 * [conversation] is the id the client views the conversation by; an agent's default conversation is viewed by its
 * `conv-default-<agent>` alias, which is how the host knows whom to push to.
 */
class FeedTimelineSource(
    private val link: FeedLink,
    agent: AgentId,
    conversation: ConversationId,
    private val scope: CoroutineScope,
    private val sink: suspend (LettaMessage) -> Unit = {},
) {
    private val agentId = agent
    private val viewedAs = conversation
    private val journalConversation = resolveConversationReadTarget(conversation)?.first ?: conversation
    private val follower = FeedFollower()
    private val projector = FeedRowProjector(agent, journalConversation)
    private val events = Channel<Event>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val mutableRows = MutableStateFlow<List<ChatRenderItem>>(emptyList())
    private val mutableMetrics = MutableStateFlow(FeedTimelineMetrics())
    private val mutablePresented = MutableStateFlow<List<ChatRenderItem>?>(null)
    private var job: Job? = null
    private val flight = FeedFlightReport(scope, journalConversation) {
        FeedPosition(follower.cursor, mutableMetrics.value.framesApplied, mutableRows.value.size)
    }

    /** The rows now, newest first, as the chat list takes them. */
    val rows: StateFlow<List<ChatRenderItem>> = mutableRows.asStateFlow()

    /** Counters since [start]. */
    val metrics: StateFlow<FeedTimelineMetrics> = mutableMetrics.asStateFlow()

    /**
     * [rows] once the first catch-up has finished (answered, refused or failed), null before: an empty list here means
     * "nothing to show", never "not read yet". What a presentation draws from.
     */
    val presented: StateFlow<List<ChatRenderItem>?> = mutablePresented.asStateFlow()

    /** The position applied so far; null before the first page. */
    val cursor: FeedCursor? get() = follower.cursor

    /** Starts following. Calling it again while following does nothing. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { link.connection.collect { if (it != null) events.trySend(Event.Connected) } }
            launch { link.pushes.mapNotNull(::decodeOrReport).filter(::isOurs).collect { events.trySend(Event.Frame(it)) } }
            for (event in events) handle(event)
        }
    }

    /** Stops following; the rows stay as they are. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: Event) {
        when (event) {
            Event.Connected -> catchUp(FeedResync.Reconnected.takeIf { follower.cursor != null })
            is Event.Frame -> follow(event.frame)
        }
    }

    private suspend fun follow(frame: FeedDeltaFrame) {
        when (val outcome = follower.onFrame(frame)) {
            is FeedFrameOutcome.Applied -> {
                bump { it.copy(framesApplied = it.framesApplied + 1) }
                outcome.entries.forEach { apply(it) }
                publish(replaced = false)
                follower.cursor?.let(flight::applied)
            }
            FeedFrameOutcome.Stale -> Unit
            is FeedFrameOutcome.Resync -> catchUp(outcome.reason)
        }
    }

    /** Reads `feed.subscribe` from the cursor until the host has no more, applying each page. */
    private suspend fun catchUp(reason: FeedResync?) {
        reason?.let { bump { m -> m.copy(resyncs = m.resyncs + 1) } }
        reason?.let(flight::resync)
        runCatchingCancellable { readUntilCaughtUp() }.onFailure { failure ->
            Telemetry.event(TAG, "read.failed", "class" attr failure::class.simpleName, level = Telemetry.Level.WARN)
        }
        if (mutablePresented.value == null) mutablePresented.value = mutableRows.value
    }

    private suspend fun readUntilCaughtUp() {
        var more = true
        while (more) {
            val page = read() ?: return
            val outcome = follower.onPage(page)
            if (outcome.replaced) projector.reset()
            outcome.entries.forEach { apply(it) }
            publish(replaced = outcome.replaced)
            more = outcome.hasMore
            flight.page(page)
        }
    }

    private suspend fun read(): FeedPage? {
        bump { it.copy(pagesRead = it.pagesRead + 1) }
        val reply = link.call(ConversationFeedWire.SUBSCRIBE, params())
        val result = reply.result
        if (!reply.success || result == null) {
            Telemetry.event(TAG, "read.refused", "error" attr reply.error, level = Telemetry.Level.WARN)
            return null
        }
        return LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), result)
    }

    private fun params(): JsonObject = buildJsonObject {
        put(ConversationFeedWire.CONVERSATION_ID, viewedAs.value)
        put(ConversationFeedWire.AGENT_ID, agentId.value)
        follower.cursor?.let { cursor ->
            put(
                ConversationFeedWire.FROM,
                buildJsonObject {
                    put(ConversationFeedWire.EPOCH, cursor.epoch)
                    put(ConversationFeedWire.SEQ, cursor.seq)
                },
            )
        }
    }

    private suspend fun apply(entry: FeedDelta) {
        flight.entry(entry)
        projector.apply(entry).forEach { sink(it) }
    }

    /** Publishes the rows when they changed, keeping every unchanged row's instance. */
    private fun publish(replaced: Boolean) {
        val previous = mutableRows.value
        val next = projector.items()
        if (next == previous) return
        val diff = FeedRowIdentity.reconcile(previous, next)
        mutableRows.value = diff.rows
        if (mutablePresented.value != null) mutablePresented.value = diff.rows
        bump {
            it.copy(
                publications = it.publications + 1,
                listReplacements = it.listReplacements + if (replaced || diff.replacement) 1 else 0,
                rowsRedrawn = it.rowsRedrawn + diff.redrawn,
            )
        }
        val replacement = replaced || diff.replacement
        // The old path's name for the same fact, so one capture compares both paths.
        val name = if (replacement) "generation.replaced" else "publication"
        Telemetry.event(
            TAG, name, "changed" attr diff.changed, "added" attr diff.added, "removed" attr diff.removed,
            level = Telemetry.Level.DEBUG,
        )
    }

    private fun bump(change: (FeedTimelineMetrics) -> FeedTimelineMetrics) {
        mutableMetrics.value = change(mutableMetrics.value)
    }

    /** The frame in [raw], or null after reporting it: a push this client cannot read is never silently lost. */
    private fun decodeOrReport(raw: JsonObject): FeedDeltaFrame? =
        runCatching { LettaJson.wire.decodeFromJsonElement(FeedDeltaFrame.serializer(), raw) }
            .onFailure(flight::undecodable)
            .getOrNull()

    /** A frame names its journal; one for another conversation or agent is not this source's (the host pushes by viewer, not by source). */
    private fun isOurs(frame: FeedDeltaFrame): Boolean =
        frame.conversation == journalConversation.value && frame.agentId == agentId.value

    private sealed interface Event {
        data object Connected : Event

        data class Frame(val frame: FeedDeltaFrame) : Event
    }

    companion object {
        /** Whether [link]'s host serves the feed and pushes to it; otherwise the caller keeps the path it had. */
        fun isAvailable(link: FeedLink): Boolean =
            link.hostCapabilities.let { ConversationFeedWire.CAPABILITY in it && ConversationFeedWire.PUSH_CAPABILITY in it }

        /**
         * The rows a presentation draws for [conversation] over [link] when its host serves and pushes the feed, else null
         * (the presentation then keeps the ledger). Each call of the result starts one source in the scope it is given.
         */
        fun rowsFor(link: FeedLink, agent: AgentId, conversation: ConversationId): FeedRowsFactory? = when {
            isAvailable(link) -> { scope -> FeedTimelineSource(link, agent, conversation, scope).also { it.start() }.presented }
            else -> null
        }

        private const val TAG = "FeedTimeline"

        /** Frames waiting for the loop; one dropped past it is a gap the next frame turns into a resync. */
        private const val EVENT_BUFFER = 64
    }
}
