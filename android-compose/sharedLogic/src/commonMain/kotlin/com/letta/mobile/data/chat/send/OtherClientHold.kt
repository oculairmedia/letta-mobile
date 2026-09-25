package com.letta.mobile.data.chat.send

import com.letta.mobile.data.runtime.isTurnAlreadyActiveMessage
import com.letta.mobile.data.transport.BridgeTurnStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-1n5py.1: the terminal of a send the wrapper bounced because ANOTHER client (a second
 * device) already runs a turn in the same conversation. The engine's lease is per conversation, so
 * this device's send never started: it gets an initiator-only busy error and a failed terminal.
 * Nothing was delivered, so the send can wait and go again.
 */
internal fun isOtherClientBusyRejection(
    status: BridgeTurnStatus,
    bufferedErrorMessage: String?,
    deliveredAssistantContent: Boolean,
): Boolean = status is BridgeTurnStatus.Failed &&
    !deliveredAssistantContent &&
    (bufferedErrorMessage == OTHER_CLIENT_BUSY_CODE || isTurnAlreadyActiveMessage(bufferedErrorMessage))

/** The error code the Iroh mapper gives a busy rejection (buffered when the message is blank). */
private const val OTHER_CLIENT_BUSY_CODE = "iroh_turn_engine_busy"

/**
 * letta-mobile-1n5py.1: when a queue held for another client's turn tries again.
 *
 * The usual release is a signal: a terminal this device does not own (the other client's turn
 * ending). This timer is the backstop for a turn whose end this device never sees. Each retry that
 * bounces again waits longer, up to [Backoff.maxMs]; a turn of this device's own that ends resets it.
 */
internal class OtherClientHold(
    private val scope: CoroutineScope,
    private val backoff: Backoff,
    private val onElapsed: suspend (QueueConversationId) -> Unit,
) {
    data class Backoff(val initialMs: Long = INITIAL_RETRY_MS, val maxMs: Long = MAX_RETRY_MS)

    private val lock = SynchronizedObject()
    private val timers = HashMap<QueueConversationId, Job>()
    private val attempts = HashMap<QueueConversationId, Int>()

    /** Arms (or re-arms) the backstop retry for [conversationId]; each re-arm waits longer. */
    fun schedule(conversationId: QueueConversationId) {
        synchronized(lock) {
            timers.remove(conversationId)?.cancel()
            val attempt = attempts[conversationId] ?: 0
            attempts[conversationId] = attempt + 1
            val waitMs = retryDelayMs(attempt)
            timers[conversationId] = scope.launch {
                delay(waitMs.milliseconds)
                synchronized(lock) { timers.remove(conversationId) }
                onElapsed(conversationId)
            }
        }
    }

    /** The hold was released by a signal: the backstop is no longer needed. */
    fun cancel(conversationId: QueueConversationId) {
        synchronized(lock) { timers.remove(conversationId)?.cancel() }
    }

    /** A turn of this device's own ended: the conversation is not someone else's any more. */
    fun reset(conversationId: QueueConversationId) {
        synchronized(lock) { attempts.remove(conversationId) }
    }

    private fun retryDelayMs(attempt: Int): Long {
        var waitMs = backoff.initialMs
        repeat(attempt) { waitMs = (waitMs * 2).coerceAtMost(backoff.maxMs) }
        return waitMs.coerceAtMost(backoff.maxMs)
    }

    private companion object {
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
