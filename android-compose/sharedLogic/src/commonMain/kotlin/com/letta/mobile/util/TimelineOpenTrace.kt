package com.letta.mobile.util

import com.letta.mobile.data.model.ConversationId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Stage-by-stage latency of one conversation open, from the navigation that asked for it to the
 * moment the list stopped changing. Measurement only: nothing here decides, delays or reorders work.
 *
 * Every stage fires at most once per open and carries the same [OpenId], so a capture orders itself
 * without joining on timestamps. Each stage is also a zero-length trace section named
 * `TimelineOpen/<stage>`, which is how the macrobenchmark reads stage times out of a Perfetto trace.
 * Events carry counts and byte totals only, never message content.
 *
 * Off by default ([Telemetry.timelineOpenTraceEnabled], or `adb shell setprop
 * log.tag.LettaTimelineOpenTrace VERBOSE`). While off, [requested] never creates an open, so every
 * later call finds nothing to mark and returns without emitting.
 */
object TimelineOpenTrace {
    /** Tag of every stage event and the prefix (`TimelineOpen/`) of its trace section. */
    const val TAG = "TimelineOpen"

    /** How long the list must go without a replacement before the open counts as settled. */
    const val SETTLE_QUIET_MS = 500L

    /** One stage of an open; [eventName] is its wire name in the event stream and the trace. */
    enum class Stage(val eventName: String) {
        /** Navigation asked for the conversation. */
        OpenRequested("open.requested"),

        /** The shared chat model bound a presentation, so the list can start reading. */
        PresentationBound("presentation.bound"),

        /** The first local ledger page read began (engine lock wait included). */
        CacheReadStart("cache.read.start"),

        /** The first local ledger page was read and projected; `count` is its row count. */
        CacheReadEnd("cache.read.end"),

        /** The first frame that drew the list with at least one row. */
        FirstFrameWithRows("first.frame.with.rows"),

        /** The recent-tail host page request left. */
        NetworkPageStart("network.page.start"),

        /** The host page came back; `count` is its records, `bytes` their decoded size. */
        NetworkPageEnd("network.page.end"),

        /** The host page was merged into the durable ledger; `count` is the rows it appended. */
        ReconcileEnd("reconcile.end"),

        /** No list replacement for [SETTLE_QUIET_MS]. */
        Settled("settled"),
    }

    /** Correlates every stage of one open. */
    @JvmInline
    value class OpenId(val value: Long)

    /**
     * Fires [Stage.FirstFrameWithRows] from a draw callback, once per open. A draw that has nothing
     * to report (no rows, tracing off, or this open already marked) costs one atomic read, so a list
     * can keep the callback installed across reopens of the same presentation.
     */
    class FirstFrameGate(private val conversation: ConversationId) {
        private val markedOpen = atomic(0L)

        /** Call from each draw of the list; [hasRows] is whether that frame shows at least one row. */
        fun onDraw(hasRows: Boolean) {
            val open = openCount.value
            if (!hasRows || open == 0L || markedOpen.value == open) return
            markedOpen.value = open
            mark(conversation, Stage.FirstFrameWithRows)
        }
    }

    private const val MAX_OPENS = 16
    private const val JOIN_WINDOW_MS = 5_000L
    private const val GIVE_UP_MS = 30_000L

    private class Open(val id: OpenId, val startedAt: ComparableTimeMark) {
        val fired = mutableSetOf<Stage>()
        var lastReplacement: ComparableTimeMark = startedAt
        var replacements = 0
    }

    /** An open still in flight and recent enough to be the one a late layer is learning about. */
    private fun Open.joinable(): Boolean = Stage.Settled !in fired && ageMs(this) < JOIN_WINDOW_MS

    private fun ageMs(open: Open): Long = (timeSource.markNow() - open.startedAt).inWholeMilliseconds

    // How many opens this process traced (0: none): the cheap read every hot-path caller (a list
    // replacement, a paging load, a draw) pays while tracing is off and nothing was ever opened.
    private val openCount = atomic(0L)
    private val lock = SynchronizedObject()
    private val opens = linkedMapOf<ConversationId, Open>()
    private var nextId = 0L

    /** Test seam: replaces the monotonic clock. */
    internal var timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic

    /** Whether tracing is on; callers that would build arguments for a call can skip it when it is not. */
    fun enabled(): Boolean = Telemetry.enabled.get() && Telemetry.isTimelineOpenTraceEnabled()

    /** Whether any open was ever traced in this process: false means every later stage call is a no-op. */
    fun tracking(): Boolean = openCount.value > 0L && enabled()

    /**
     * Whether [conversation] has an open that can still settle: unsettled and younger than
     * [GIVE_UP_MS]. An open that never settles (the host never answered) stops being polled and
     * simply has no `settled` event.
     */
    fun isActive(conversation: ConversationId): Boolean = tracking() && synchronized(lock) {
        opens[conversation]?.let { Stage.Settled !in it.fired && ageMs(it) < GIVE_UP_MS } ?: false
    }

    /** Starts a new open of [conversation], replacing any earlier one. Returns null while tracing is off. */
    fun requested(conversation: ConversationId): OpenId? {
        if (!enabled()) return null
        return synchronized(lock) { begin(conversation) }
    }

    /**
     * Starts an open unless [conversation] already has an unsettled one, so a later layer that also
     * learns of the open (the chat model, after navigation) joins the navigation's open instead of
     * restarting it.
     */
    fun requestedIfAbsent(conversation: ConversationId): OpenId? {
        if (!enabled()) return null
        return synchronized(lock) {
            opens[conversation]?.takeIf { it.joinable() }?.id ?: begin(conversation)
        }
    }

    /**
     * Records [stage] for [conversation]'s open, once. [count] and [bytes] are omitted when null.
     * Returns whether this call was the one that recorded it, so a caller can pair its own end with
     * its own start when two requests of one open overlap.
     */
    fun mark(conversation: ConversationId, stage: Stage, count: Int? = null, bytes: Long? = null): Boolean {
        if (!tracking()) return false
        return synchronized(lock) {
            val open = opens[conversation]
            val first = open != null && open.fired.add(stage)
            if (first) emit(conversation, requireNotNull(open), stage, count, bytes)
            first
        }
    }

    /** Notes that the list was replaced, which restarts the quiet period [settleIfQuiet] checks. */
    fun listReplaced(conversation: ConversationId) {
        if (!tracking()) return
        synchronized(lock) {
            val open = opens[conversation] ?: return
            if (Stage.Settled in open.fired) return
            open.lastReplacement = timeSource.markNow()
            open.replacements++
        }
    }

    /**
     * Records [Stage.Settled] when the list has been quiet for [SETTLE_QUIET_MS], rows have been
     * drawn and the host page has come back (or failed); otherwise does nothing. Without the last
     * two a quiet moment between the cached rows and the network's rows would end the open early.
     * `elapsedMs` (like the stage's trace section) is when this was recorded, at least
     * [SETTLE_QUIET_MS] after the last replacement; `lastReplacementMs` is when the list last changed.
     */
    fun settleIfQuiet(conversation: ConversationId) {
        if (!tracking()) return
        synchronized(lock) {
            val open = opens[conversation] ?: return
            val quiet = (timeSource.markNow() - open.lastReplacement).inWholeMilliseconds >= SETTLE_QUIET_MS
            val ready = Stage.FirstFrameWithRows in open.fired && Stage.NetworkPageEnd in open.fired
            if (quiet && ready && open.fired.add(Stage.Settled)) {
                emit(conversation, open, Stage.Settled, open.replacements, null, lastReplacement = open.lastReplacement)
            }
        }
    }

    internal fun clearForTest() = synchronized(lock) {
        opens.clear()
        openCount.value = 0L
        nextId = 0L
        timeSource = TimeSource.Monotonic
    }

    private fun begin(conversation: ConversationId): OpenId {
        val open = Open(OpenId(++nextId), timeSource.markNow())
        opens.remove(conversation)
        opens[conversation] = open
        openCount.value = nextId
        while (opens.size > MAX_OPENS) opens.remove(opens.keys.first())
        open.fired += Stage.OpenRequested
        emit(conversation, open, Stage.OpenRequested, null, null)
        return open.id
    }

    private fun emit(
        conversation: ConversationId,
        open: Open,
        stage: Stage,
        count: Int?,
        bytes: Long?,
        lastReplacement: ComparableTimeMark? = null,
    ) {
        val elapsed: Duration = timeSource.markNow() - open.startedAt
        val section = "$TAG/${stage.eventName}"
        if (Telemetry.beginTraceSection(section)) Telemetry.endTraceSection()
        val attrs = buildList {
            add("openId" attr open.id.value)
            add("conversationId" attr conversation.value)
            add("elapsedMs" attr elapsed.inWholeMilliseconds)
            add("elapsedUs" attr elapsed.inWholeMicroseconds)
            if (count != null) add("count" attr count)
            if (bytes != null) add("bytes" attr bytes)
            if (lastReplacement != null) add("lastReplacementMs" attr (lastReplacement - open.startedAt).inWholeMilliseconds)
        }
        Telemetry.event(TAG, stage.eventName, *attrs.toTypedArray())
    }
}
