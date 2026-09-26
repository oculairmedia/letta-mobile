package com.letta.mobile.data.chat.send

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** What [QueuedSendDriver] needs from the coordinator that owns the turn state. */
internal interface QueuedSendTurns {
    /** Runs [block] under the coordinator's turn-state lock. */
    suspend fun <T> serialized(block: suspend () -> T): T

    /** The transport still owns a turn for [conversationId]. */
    fun hasActiveTurn(conversationId: QueueConversationId): Boolean

    /** Asks the transport to abort [conversationId]'s turn; false when it had none to abort. */
    fun abortTurn(conversationId: QueueConversationId): Boolean

    /** Dispatches [item] as a new turn (optimistic row included); false when not accepted. Locked. */
    suspend fun dispatch(item: QueuedChatSend): Boolean
}

/** The queue transitions [QueuedSendDriver] reports. */
private enum class QueueEvent(val wireName: String) {
    Paused("queue.paused"),
    Cancelled("queue.cancelled"),
    Resumed("queue.resumed"),
    SendNow("queue.sendNow"),
    Dispatched("queue.dispatched"),
    DrainDeferred("queue.drainDeferred"),
    HeldByOtherClient("queue.heldByOtherClient"),
    OtherClientReleased("queue.otherClientReleased"),
}

/**
 * letta-mobile-1n5py: runs the [ChatSendQueue] against the live transport.
 *
 * A message sent while its conversation is busy is queued here instead of failing. The head runs
 * once the running turn is over: after its terminal ([onTurnFinishedLocked]), on [resume], or right
 * after the current turn is aborted to push an item through ([sendNow]). A Stop pauses the queue
 * ([onStopped]), matching the App Server, which parks its own queue on `abort_message`.
 *
 * The transport retires a turn a moment after its terminal frame is published, so a drain that
 * still sees the turn retries briefly instead of dispatching into a busy rejection. It gives up
 * after [retryAttempts]: the items stay queued and the next terminal, send or resume drains again.
 */
internal class QueuedSendDriver(
    private val scope: CoroutineScope,
    private val queue: ChatSendQueue,
    private val turns: QueuedSendTurns,
    private val retry: DrainRetry = DrainRetry(),
    otherClientBackoff: OtherClientHold.Backoff = OtherClientHold.Backoff(),
) {
    /** How long a drain keeps retrying a conversation whose turn has not retired yet. */
    data class DrainRetry(val delayMs: Long = DRAIN_RETRY_DELAY_MS, val attempts: Int = DRAIN_RETRY_ATTEMPTS)

    private val drainLock = SynchronizedObject()
    private val drains = HashMap<QueueConversationId, Job>()
    private val otherClientHold = OtherClientHold(scope, otherClientBackoff) { conversationId ->
        turns.serialized { releaseOtherClientHoldLocked(conversationId) }
    }

    /** Queues [item] (caller holds the turn lock); returns its 1-based position. */
    suspend fun enqueueLocked(item: QueuedChatSend): Int {
        val position = queue.enqueue(item)
        // A new send is the "next input" that releases a paused queue on the App Server too.
        queue.resume(item.conversationId)
        if (!turns.hasActiveTurn(item.conversationId)) drainLocked(item.conversationId)
        return position
    }

    /** The conversation's turn reached its terminal (caller holds the turn lock). */
    suspend fun onTurnFinishedLocked(conversationId: QueueConversationId) {
        otherClientHold.reset(conversationId)
        if (queue.hasItems(conversationId)) drainLocked(conversationId)
    }

    /**
     * letta-mobile-1n5py.1: [item] bounced off another client's turn in its conversation (caller
     * holds the turn lock). It waits at the head for that turn's end, never failing.
     */
    fun holdForOtherClientLocked(item: QueuedChatSend) {
        queue.holdForOtherClient(item)
        report(QueueEvent.HeldByOtherClient, item.conversationId, item.id)
        otherClientHold.schedule(item.conversationId)
    }

    /** A terminal this device does not own: another client's turn may have ended, so try again. */
    suspend fun onForeignTerminalLocked() {
        queue.heldConversations().forEach { releaseOtherClientHoldLocked(it) }
    }

    private suspend fun releaseOtherClientHoldLocked(conversationId: QueueConversationId) {
        otherClientHold.cancel(conversationId)
        if (!queue.releaseOtherClientHold(conversationId)) return
        report(QueueEvent.OtherClientReleased, conversationId)
        drainLocked(conversationId)
    }

    /** A user Stop: hold what is queued. */
    fun onStopped(conversationId: QueueConversationId) {
        if (queue.pause(conversationId)) report(QueueEvent.Paused, conversationId)
    }

    /** A terminal disconnect: hold every queue rather than drop what the user typed. */
    fun onDisconnected() {
        queue.pauseAll().forEach { report(QueueEvent.Paused, it) }
    }

    suspend fun cancel(id: QueuedSendId): Boolean = turns.serialized {
        val removed = queue.remove(id) ?: return@serialized false
        report(QueueEvent.Cancelled, removed.conversationId, id)
        true
    }

    suspend fun resume(conversationId: QueueConversationId) = turns.serialized {
        queue.resume(conversationId)
        report(QueueEvent.Resumed, conversationId)
        drainLocked(conversationId)
    }

    /**
     * Makes [id] the next message to run and ends the current turn so it runs now. Items it
     * jumps keep their order behind it. With no turn running it simply drains.
     */
    suspend fun sendNow(id: QueuedSendId): Boolean = turns.serialized {
        val conversationId = queue.conversationOf(id) ?: return@serialized false
        queue.promote(id)
        queue.resume(conversationId)
        queue.releaseOtherClientHold(conversationId)
        report(QueueEvent.SendNow, conversationId, id)
        // An aborted turn drains from its own terminal; without one, drain now.
        if (!abortRunningTurn(conversationId)) drainLocked(conversationId)
        true
    }

    private fun abortRunningTurn(conversationId: QueueConversationId): Boolean =
        turns.hasActiveTurn(conversationId) && turns.abortTurn(conversationId)

    /** Runs the head now if it can; otherwise retries briefly off the lock. */
    private suspend fun drainLocked(conversationId: QueueConversationId) {
        if (!drainOnceLocked(conversationId)) scheduleDrain(conversationId)
    }

    private fun scheduleDrain(conversationId: QueueConversationId) {
        synchronized(drainLock) {
            if (drains[conversationId]?.isActive == true) return
            val job = scope.launch { drainWithRetry(conversationId) }
            drains[conversationId] = job
            job.invokeOnCompletion {
                synchronized(drainLock) { if (drains[conversationId] === job) drains.remove(conversationId) }
            }
        }
    }

    private suspend fun drainWithRetry(conversationId: QueueConversationId) {
        repeat(retry.attempts) {
            delay(retry.delayMs.milliseconds)
            if (turns.serialized { drainOnceLocked(conversationId) }) return
        }
        report(QueueEvent.DrainDeferred, conversationId)
    }

    /** True when the drain is finished (dispatched, or nothing runnable); false to retry. */
    private suspend fun drainOnceLocked(conversationId: QueueConversationId): Boolean {
        if (turns.hasActiveTurn(conversationId)) return false
        val next = queue.takeNext(conversationId) ?: return true
        if (turns.dispatch(next)) {
            report(QueueEvent.Dispatched, conversationId, next.id)
            return true
        }
        queue.putBack(next)
        return false
    }

    private fun report(event: QueueEvent, conversationId: QueueConversationId, id: QueuedSendId? = null) {
        Telemetry.event(
            TELEMETRY_TAG, event.wireName,
            "conversationId" to conversationId.value,
            "otid" to id?.value,
            "depth" to queue.queueFor(conversationId).items.size,
        )
    }

    private companion object {
        const val TELEMETRY_TAG = "ChatSendQueue"
        const val DRAIN_RETRY_DELAY_MS = 50L
        const val DRAIN_RETRY_ATTEMPTS = 40
    }
}
