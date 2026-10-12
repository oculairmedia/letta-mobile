package com.letta.mobile.data.chat.approval

import com.letta.mobile.util.Telemetry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How a submitted answer was handled. */
enum class OutboxSubmitResult {
    /** Sent and taken by the host. */
    Sent,

    /** The connection is down: queued, sent on reconnect. */
    Queued,

    /** This tool call was already answered or queued: nothing more is sent. */
    Duplicate,

    /** The host no longer holds the request. */
    NoLongerPending,
}

/** What the person is told about queued answers. */
sealed interface OutboxNotice {
    /** [count] queued answers went out after the reconnect. */
    data class Sent(val count: Int) : OutboxNotice

    /** [count] queued answers were no longer needed (the request was answered or ended meanwhile). */
    data class Dropped(val count: Int) : OutboxNotice
}

/**
 * letta-mobile-bzvro.14 (F14): routes approval answers through the [outbox]: sent at once while
 * [connected], queued otherwise (or when the send fails on the connection), and flushed after each
 * reconnect once the host has had [settle] to replay what it still holds (its `pending_approvals`
 * snapshot, which [stillPending] reads).
 */
class ApprovalOutboxCoordinator(
    private val scope: CoroutineScope,
    val outbox: ApprovalOutbox,
    private val connected: StateFlow<Boolean>,
    private val stillPending: (OutboxApproval) -> Boolean?,
    private val send: suspend (OutboxApproval) -> OutboxSendOutcome,
    private val settle: Duration = DEFAULT_SETTLE,
) {
    private val _notices = MutableSharedFlow<OutboxNotice>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<OutboxNotice> = _notices.asSharedFlow()

    /** The request ids answered offline and still waiting, for the cards' queued state. */
    val queuedRequestIds: StateFlow<Set<String>> = outbox.entries
        .map { snapshot -> snapshot.entries.mapTo(HashSet()) { it.requestId } }
        .stateIn(scope, SharingStarted.Eagerly, outbox.entries.value.entries.mapTo(HashSet()) { it.requestId })

    private val reconnects: Job = scope.launch {
        connected.collect { up ->
            if (up && outbox.entries.value.entries.isNotEmpty()) {
                delay(settle)
                flushNow()
            }
        }
    }

    suspend fun submit(entry: OutboxApproval): OutboxSubmitResult {
        if (!connected.value) return queue(entry)
        if (entry.key in outbox.entries.value.answered) return OutboxSubmitResult.Duplicate
        return when (attempt(entry)) {
            OutboxSendOutcome.Accepted -> OutboxSubmitResult.Sent.also { outbox.markAnswered(entry) }
            OutboxSendOutcome.NoLongerPending -> OutboxSubmitResult.NoLongerPending
            OutboxSendOutcome.Offline -> queue(entry)
        }
    }

    /** One send now. A failure on a live connection is the caller's error to show; a dropped one queues it. */
    private suspend fun attempt(entry: OutboxApproval): OutboxSendOutcome = try {
        send(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        if (connected.value) throw error
        OutboxSendOutcome.Offline
    }

    /** Sends what is queued now (also called on reconnect). */
    suspend fun flushNow(): OutboxFlushReport {
        val report = outbox.flush(stillPending, send)
        Telemetry.event("ApprovalOutbox", "flushed", "sent" to report.sent.size, "dropped" to report.dropped.size, "queued" to report.stillQueued.size)
        if (report.sent.isNotEmpty()) _notices.tryEmit(OutboxNotice.Sent(report.sent.size))
        if (report.dropped.isNotEmpty()) _notices.tryEmit(OutboxNotice.Dropped(report.dropped.size))
        return report
    }

    fun close() {
        reconnects.cancel()
    }

    private fun queue(entry: OutboxApproval): OutboxSubmitResult =
        if (outbox.enqueue(entry)) OutboxSubmitResult.Queued else OutboxSubmitResult.Duplicate

    companion object {
        val DEFAULT_SETTLE: Duration = 1.seconds
        private const val NOTICE_BUFFER = 8
    }
}
