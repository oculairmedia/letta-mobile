package com.letta.mobile.util

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.TimelineOpenTrace.Stage
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

private const val MAX_OPENS = 16
private const val JOIN_WINDOW_MS = 5_000L
private const val GIVE_UP_MS = 30_000L

/** One traced open: which stages fired and when its list last changed. */
internal class Open(val id: TimelineOpenTrace.OpenId, val startedAt: ComparableTimeMark) {
    val fired = mutableSetOf<Stage>()
    var lastReplacement: ComparableTimeMark = startedAt
    var replacements = 0

    /** Whether the open's list still counts replacements (it has not settled). */
    val settling: Boolean get() = Stage.Settled !in fired

    /** Whether the host page came back and rows were drawn, the two things a quiet period alone cannot show. */
    val hasRowsAndHostPage: Boolean
        get() = fired.containsAll(listOf(Stage.FirstFrameWithRows, Stage.NetworkPageEnd))
}

/**
 * The opens [TimelineOpenTrace] has seen, newest last and bounded. One instance serves the process;
 * every method takes the lock, and none is called unless tracing is on.
 */
internal class OpenLedger {
    var clock: TimeSource.WithComparableMarks = TimeSource.Monotonic

    /** The id of the newest open (0: none), readable without the lock. */
    val opened = atomic(0L)
    private val lock = SynchronizedObject()
    private val opens = linkedMapOf<ConversationId, Open>()
    private var nextId = 0L

    fun begin(conversation: ConversationId): TimelineOpenTrace.OpenId = synchronized(lock) { start(conversation) }

    fun beginUnlessJoinable(conversation: ConversationId): TimelineOpenTrace.OpenId = synchronized(lock) {
        opens[conversation]?.takeIf { it.joinable() }?.id ?: start(conversation)
    }

    fun canSettle(conversation: ConversationId): Boolean = synchronized(lock) {
        opens[conversation]?.let { it.settling && it.ageMs() < GIVE_UP_MS } ?: false
    }

    fun mark(conversation: ConversationId, stage: Stage, count: Int?, bytes: Long?): Boolean = synchronized(lock) {
        val open = opens[conversation] ?: return@synchronized false
        val first = open.fired.add(stage)
        if (first) emit(conversation, open, stage, Detail(count, bytes))
        first
    }

    fun listReplaced(conversation: ConversationId) = synchronized(lock) {
        opens[conversation]?.takeIf { it.settling }?.let {
            it.lastReplacement = clock.markNow()
            it.replacements++
        }
        Unit
    }

    fun settleIfQuiet(conversation: ConversationId) = synchronized(lock) {
        val open = opens[conversation]?.takeIf { it.isQuiet() && it.hasRowsAndHostPage } ?: return@synchronized
        if (open.fired.add(Stage.Settled)) {
            emit(conversation, open, Stage.Settled, Detail(open.replacements, null, open.lastReplacement))
        }
    }

    fun clear() = synchronized(lock) {
        opens.clear()
        opened.value = 0L
        nextId = 0L
        clock = TimeSource.Monotonic
    }

    private fun start(conversation: ConversationId): TimelineOpenTrace.OpenId {
        val open = Open(TimelineOpenTrace.OpenId(++nextId), clock.markNow())
        opens.remove(conversation)
        opens[conversation] = open
        opened.value = nextId
        while (opens.size > MAX_OPENS) opens.remove(opens.keys.first())
        open.fired += Stage.OpenRequested
        emit(conversation, open, Stage.OpenRequested, Detail())
        return open.id
    }

    private fun Open.ageMs(): Long = (clock.markNow() - startedAt).inWholeMilliseconds

    private fun Open.isQuiet(): Boolean =
        (clock.markNow() - lastReplacement).inWholeMilliseconds >= TimelineOpenTrace.SETTLE_QUIET_MS

    /** An open still in flight and recent enough to be the one a late layer is learning about. */
    private fun Open.joinable(): Boolean = settling && ageMs() < JOIN_WINDOW_MS

    private fun emit(conversation: ConversationId, open: Open, stage: Stage, detail: Detail) {
        val elapsed = clock.markNow() - open.startedAt
        // A zero-length section is how the macrobenchmark reads this stage out of a Perfetto trace.
        if (Telemetry.beginTraceSection("${TimelineOpenTrace.TAG}/${stage.eventName}")) Telemetry.endTraceSection()
        val attrs = listOf(
            "openId" attr open.id.value,
            "conversationId" attr conversation.value,
            "elapsedMs" attr elapsed.inWholeMilliseconds,
            "elapsedUs" attr elapsed.inWholeMicroseconds,
        ) + detail.attributes(open)
        Telemetry.event(TimelineOpenTrace.TAG, stage.eventName, attrs = attrs.toTypedArray())
    }
}

/** What a stage adds to the common attributes; each is omitted when absent. */
internal class Detail(
    private val count: Int? = null,
    private val bytes: Long? = null,
    private val lastReplacement: ComparableTimeMark? = null,
) {
    fun attributes(open: Open) = listOfNotNull(
        count?.let { "count" attr it },
        bytes?.let { "bytes" attr it },
        lastReplacement?.let { "lastReplacementMs" attr (it - open.startedAt).inWholeMilliseconds },
    )
}
