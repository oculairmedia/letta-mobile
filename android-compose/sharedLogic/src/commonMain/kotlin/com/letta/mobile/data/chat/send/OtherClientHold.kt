package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.runtime.isTurnAlreadyActiveMessage
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The terminal of a send the wrapper bounced because ANOTHER client (a second
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

/** [send] when its terminal is [isOtherClientBusyRejection]: it never started and can wait. */
internal fun otherClientBounce(
    send: QueuedChatSend?,
    status: BridgeTurnStatus,
    bufferedErrorMessage: String?,
    deliveredAssistantContent: Boolean,
): QueuedChatSend? = send?.takeIf { isOtherClientBusyRejection(status, bufferedErrorMessage, deliveredAssistantContent) }

/** The error code the Iroh mapper gives a busy rejection (buffered when the message is blank). */
private const val OTHER_CLIENT_BUSY_CODE = "iroh_turn_engine_busy"

/**
 * When a queue held for another client's turn tries again.
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
}

private const val INITIAL_RETRY_MS = 2_000L
private const val MAX_RETRY_MS = 30_000L

/**
 * Keeps the "queued on server" mark of a sent message. The App Server parks
 * this device's input behind a previous turn ([WsTimelineEvent.TurnQueued]); a dequeue start or live
 * frame of this device's own turn releases the mark, with its identified terminal as a backstop.
 */
internal class ServerQueueMarks(
    private val queue: ChatSendQueue,
    private val sendOfTurn: (String) -> QueuedChatSend?,
    private val sendOfConversation: (String) -> QueuedChatSend?,
) {
    private val lock = SynchronizedObject()

    /** Conversations where this device's own turn was cancelled and may still be finishing. */
    private val stoppingOwnTurn = HashSet<QueueConversationId>()
    private val startedOwnTurns = HashMap<QueueConversationId, QueuedSendId>()

    /** Retains the locally submitted predecessor before acceptance replaces its conversation state. */
    fun rememberOwnTurn(send: QueuedChatSend?) {
        send?.let { synchronized(lock) { startedOwnTurns[it.conversationId] = it.id } }
    }

    /**
     * This device's send waits in the App Server queue: behind its own just-stopped turn when one
     * is still winding down there, or behind a locally submitted previous response. Without local
     * predecessor evidence the queue keeps its other-client classification.
     */
    fun markQueuedOnServer(event: WsTimelineEvent.TurnQueued) {
        val send = sendOfTurn(event.turnId) ?: sendOfConversation(event.conversationId) ?: return
        val behind = synchronized(lock) {
            val previous = startedOwnTurns[send.conversationId]
            // A delayed/repeated queue notice cannot re-park an input already observed starting.
            if (previous == send.id) return
            when {
                send.conversationId in stoppingOwnTurn -> ServerQueueBlocker.OwnStoppingTurn
                previous != null -> ServerQueueBlocker.OwnRunningTurn
                else -> ServerQueueBlocker.OtherClient
            }
        }
        queue.markQueuedOnServer(send, behind)
    }

    /**
     * The notice for a turn of this device's own that ended cancelled; its
     * run may still be winding down on the server, so a send queued next waits for it, not for
     * another device ([markQueuedOnServer]).
     */
    fun cancelledTerminalNotice(send: QueuedChatSend?): TurnFailureNotice {
        send?.let { synchronized(lock) { stoppingOwnTurn += it.conversationId } }
        return TurnFailureNotices.forCancelledTerminal()
    }

    /** A live frame of this device's own turn: a send the server had queued has started. */
    fun onOwnTurnFrame(event: WsTimelineEvent.MessageDelta) {
        if (event.isReplay || event.message is UserMessage) return
        val send = event.turnId?.let(sendOfTurn) ?: return
        synchronized(lock) { stoppingOwnTurn -= send.conversationId }
        rememberOwnTurn(send)
        release(TurnTail(event.turnId, event.message.runId))
    }

    /** A start after queueing releases the mark; the transport's pre-acceptance start does not. */
    fun onTurnStarted(event: WsTimelineEvent.TurnStarted) {
        if (event.isReplay) return
        if (sendOfConversation(event.conversationId) == null) {
            val conversation = QueueConversationId(event.conversationId)
            synchronized(lock) {
                startedOwnTurns -= conversation
                stoppingOwnTurn -= conversation
            }
        }
        release(TurnTail(event.turnId, event.runId))
    }

    /** Exact local-turn or client-message identity proves this queued input has started or ended. */
    fun release(tail: TurnTail) {
        val send = tail.turnId?.let(sendOfTurn) ?: return
        if (queue.queueFor(send.conversationId).queuedOnServer?.id != send.id) return
        synchronized(lock) { stoppingOwnTurn -= send.conversationId }
        rememberOwnTurn(send)
        queue.clearQueuedOnServer(send.conversationId)
    }
}
