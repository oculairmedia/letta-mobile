package com.letta.mobile.data.chat.approval

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * letta-mobile-bzvro.14 (F14, G47): one approval answer made while the connection was down,
 * waiting to be sent. Keyed by [key] (the tool call it answers, else the request id), which is what
 * makes a replay idempotent: one answer per tool call, ever.
 */
@Immutable
@Serializable
data class OutboxApproval(
    val key: String,
    val agentId: String,
    val conversationId: String,
    val requestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String? = null,
    val selectedSuggestionIds: List<String> = emptyList(),
    val queuedAtMs: Long = 0L,
    val state: OutboxApprovalState = OutboxApprovalState.Queued,
) {
    companion object {
        /** [answer] for [target]'s conversation, keyed by the tool call it answers. */
        fun of(target: OutboxTarget, answer: ChatApprovalAnswer) = OutboxApproval(
            key = answer.toolCallIds.firstOrNull() ?: answer.requestId,
            agentId = target.agentId,
            conversationId = target.conversationId,
            requestId = answer.requestId,
            toolCallIds = answer.toolCallIds,
            approve = answer.approve,
            reason = answer.reason,
            selectedSuggestionIds = answer.selectedSuggestionIds,
        )
    }
}

/** The conversation an outbox answer belongs to. */
data class OutboxTarget(val agentId: String, val conversationId: String)

@Serializable
enum class OutboxApprovalState {
    /** Waiting for the connection. */
    Queued,

    /** Handed to the sender; the outcome is not known yet. */
    Sending,
}

/** How one send of a queued answer ended. */
enum class OutboxSendOutcome {
    /** The host took the answer. */
    Accepted,

    /** The host no longer holds the request (answered elsewhere, its tool ran, a stale gate). */
    NoLongerPending,

    /** The connection failed again: the answer stays queued for the next reconnect. */
    Offline,
}

/** What one flush did, for the UI's notices and for tests. */
data class OutboxFlushReport(
    val sent: List<String> = emptyList(),
    val dropped: List<String> = emptyList(),
    val stillQueued: List<String> = emptyList(),
)

/** Where the outbox survives an app restart. */
interface ApprovalOutboxStore {
    fun load(): ApprovalOutboxSnapshot
    fun save(snapshot: ApprovalOutboxSnapshot)
}

@Serializable
data class ApprovalOutboxSnapshot(
    val entries: List<OutboxApproval> = emptyList(),
    /** Keys whose answer was accepted, newest last; bounded. A key here is never answered again. */
    val answered: List<String> = emptyList(),
)

/** The outbox in the app's settings store, as JSON under one key. */
class SettingsApprovalOutboxStore(
    private val store: SecureSettingsStore,
    private val key: String = KEY,
) : ApprovalOutboxStore {
    override fun load(): ApprovalOutboxSnapshot =
        runCatching { store.getString(key)?.let { json.decodeFromString(ApprovalOutboxSnapshot.serializer(), it) } }
            .onFailure { Telemetry.event("ApprovalOutbox", "load.failed", "error" to it.message, level = Telemetry.Level.WARN) }
            .getOrNull() ?: ApprovalOutboxSnapshot()

    override fun save(snapshot: ApprovalOutboxSnapshot) {
        runCatching { store.putString(key, json.encodeToString(ApprovalOutboxSnapshot.serializer(), snapshot)) }
            .onFailure { Telemetry.event("ApprovalOutbox", "save.failed", "error" to it.message, level = Telemetry.Level.WARN) }
    }

    companion object {
        const val KEY = "approvals.offline_outbox"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** An outbox that lives only as long as the process (tests, previews). */
class InMemoryApprovalOutboxStore(private var snapshot: ApprovalOutboxSnapshot = ApprovalOutboxSnapshot()) : ApprovalOutboxStore {
    override fun load(): ApprovalOutboxSnapshot = snapshot

    override fun save(snapshot: ApprovalOutboxSnapshot) {
        this.snapshot = snapshot
    }
}

/**
 * letta-mobile-bzvro.14 (F14): answers made while disconnected, persisted in [store] and replayed
 * once the connection is back.
 *
 * The rules, each from the acceptance criteria:
 * - Exactly one answer per tool call: [enqueue] refuses a key that is already queued or was already
 *   answered, and [flush] skips a key answered while it waited.
 * - A replay never answers a request the host no longer holds: [flush] asks `stillPending` first and
 *   drops the entry (reported in [OutboxFlushReport.dropped]) when the host says no, and drops it too
 *   when the host refuses the answer as no longer pending.
 * - A send that fails on the connection again leaves the entry queued; nothing is lost.
 * - Flushes never overlap ([Mutex]), so two reconnect signals cannot send the same entry twice.
 */
class ApprovalOutbox(
    private val store: ApprovalOutboxStore,
    private val clock: () -> Long = { 0L },
) {
    private val state = MutableStateFlow(store.load().let { it.copy(entries = it.entries.map { e -> e.copy(state = OutboxApprovalState.Queued) }) })
    private val flushLock = Mutex()

    /** Every queued answer, oldest first. */
    val entries: StateFlow<ApprovalOutboxSnapshot> = state.asStateFlow()

    fun queuedFor(conversationId: String): List<OutboxApproval> = state.value.entries.filter { it.conversationId == conversationId }

    /** Queues [entry]; false when its tool call is already queued or answered (it would answer twice). */
    fun enqueue(entry: OutboxApproval): Boolean {
        var added = false
        update { snapshot ->
            val duplicate = snapshot.entries.any { it.key == entry.key } || entry.key in snapshot.answered
            added = !duplicate
            if (duplicate) snapshot else snapshot.copy(entries = snapshot.entries + entry.copy(queuedAtMs = clock(), state = OutboxApprovalState.Queued))
        }
        Telemetry.event("ApprovalOutbox", if (added) "enqueued" else "duplicate")
        return added
    }

    /** An answer for [entry]'s tool call was accepted: never send a queued one for it. */
    fun markAnswered(entry: OutboxApproval) {
        update { snapshot -> snapshot.answering(entry.key).copy(entries = snapshot.entries.filterNot { it.key == entry.key }) }
    }

    /** Drops [entry] from the queue without sending it. */
    fun cancel(entry: OutboxApproval) {
        update { snapshot -> snapshot.copy(entries = snapshot.entries.filterNot { it.key == entry.key }) }
    }

    /**
     * Sends every queued answer in order. [stillPending] says whether the host still holds the
     * request (null when it cannot tell: the answer is sent and the host decides).
     */
    suspend fun flush(
        stillPending: (OutboxApproval) -> Boolean?,
        send: suspend (OutboxApproval) -> OutboxSendOutcome,
    ): OutboxFlushReport = flushLock.withLock {
        val report = OutboxFlushReport()
        state.value.entries.fold(report) { acc, entry -> acc + flushOne(entry, stillPending, send) }
    }

    private suspend fun flushOne(
        entry: OutboxApproval,
        stillPending: (OutboxApproval) -> Boolean?,
        send: suspend (OutboxApproval) -> OutboxSendOutcome,
    ): OutboxFlushReport {
        if (entry.key in state.value.answered || stillPending(entry) == false) {
            cancel(entry)
            return OutboxFlushReport(dropped = listOf(entry.key))
        }
        setState(entry, OutboxApprovalState.Sending)
        val outcome = try {
            send(entry)
        } catch (cancelled: CancellationException) {
            setState(entry, OutboxApprovalState.Queued)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Telemetry.event("ApprovalOutbox", "send.failed", "class" to error::class.simpleName, level = Telemetry.Level.WARN)
            OutboxSendOutcome.Offline
        }
        return when (outcome) {
            OutboxSendOutcome.Accepted -> OutboxFlushReport(sent = listOf(entry.key)).also { markAnswered(entry) }
            OutboxSendOutcome.NoLongerPending -> OutboxFlushReport(dropped = listOf(entry.key)).also { cancel(entry) }
            OutboxSendOutcome.Offline -> OutboxFlushReport(stillQueued = listOf(entry.key)).also { setState(entry, OutboxApprovalState.Queued) }
        }
    }

    private fun setState(entry: OutboxApproval, next: OutboxApprovalState) {
        update { snapshot -> snapshot.copy(entries = snapshot.entries.map { if (it.key == entry.key) it.copy(state = next) else it }) }
    }

    private fun update(change: (ApprovalOutboxSnapshot) -> ApprovalOutboxSnapshot) {
        val next = synchronizedUpdate(change)
        store.save(next)
    }

    private fun synchronizedUpdate(change: (ApprovalOutboxSnapshot) -> ApprovalOutboxSnapshot): ApprovalOutboxSnapshot {
        while (true) {
            val current = state.value
            val next = change(current)
            if (state.compareAndSet(current, next)) return next
        }
    }

    private fun ApprovalOutboxSnapshot.answering(key: String) =
        copy(answered = (answered - key + key).takeLast(MAX_ANSWERED))

    private operator fun OutboxFlushReport.plus(other: OutboxFlushReport) = OutboxFlushReport(
        sent = sent + other.sent,
        dropped = dropped + other.dropped,
        stillQueued = stillQueued + other.stillQueued,
    )

    private companion object {
        const val MAX_ANSWERED = 500
    }
}
