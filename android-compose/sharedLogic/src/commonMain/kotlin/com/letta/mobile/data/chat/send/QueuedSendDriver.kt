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
    fun hasActiveTurn(conversationId: String): Boolean

    /** Asks the transport to abort [conversationId]'s turn; false when it had none to abort. */
    fun abortTurn(conversationId: String): Boolean

    /** Dispatches [item] as a new turn (optimistic row included); false when not accepted. Locked. */
    suspend fun dispatch(item: QueuedChatSend): Boolean
}

/**
 * letta-mobile-1n5py: runs the [ChatSendQueue] against the live transport.
 *
 * A message sent while its conversation is busy is queued here instead of failing. The head runs
 * once the running turn is over: after its terminal ([onTurnFinished]), on [resume], or right after
 * the current turn is aborted to push an item through ([sendNow]). A Stop pauses the queue
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
    private val retryDelayMs: Long = DRAIN_RETRY_DELAY_MS,
    private val retryAttempts: Int = DRAIN_RETRY_ATTEMPTS,
) {
    private val drainLock = SynchronizedObject()
    private val drains = HashMap<String, Job>()

    /** Queues [item] (caller holds the turn lock); returns its 1-based position. */
    suspend fun enqueueLocked(item: QueuedChatSend): Int {
        val position = queue.enqueue(item)
        // A new send is the "next input" that releases a paused queue on the App Server too.
        queue.resume(item.conversationId)
        if (!turns.hasActiveTurn(item.conversationId)) drainLocked(item.conversationId)
        return position
    }

    /** The conversation's turn reached its terminal (caller holds the turn lock). */
    suspend fun onTurnFinishedLocked(conversationId: String) {
        if (queue.hasItems(conversationId)) drainLocked(conversationId)
    }

    /** A user Stop: hold what is queued. */
    fun onStopped(conversationId: String) {
        if (queue.pause(conversationId)) telemetry("queue.paused", conversationId)
    }

    /** A terminal disconnect: hold every queue rather than drop what the user typed. */
    fun onDisconnected() {
        queue.pauseAll().forEach { telemetry("queue.paused", it, "reason" to "disconnect") }
    }

    suspend fun cancel(otid: String): Boolean = turns.serialized {
        val removed = queue.remove(otid) ?: return@serialized false
        telemetry("queue.cancelled", removed.conversationId, "otid" to otid)
        true
    }

    suspend fun resume(conversationId: String) = turns.serialized {
        queue.resume(conversationId)
        telemetry("queue.resumed", conversationId)
        drainLocked(conversationId)
    }

    /**
     * Makes [otid] the next message to run and ends the current turn so it runs now. Items it
     * jumps keep their order behind it. With no turn running it simply drains.
     */
    suspend fun sendNow(otid: String): Boolean = turns.serialized {
        val conversationId = queue.conversationOf(otid) ?: return@serialized false
        queue.promote(otid)
        queue.resume(conversationId)
        val aborted = turns.hasActiveTurn(conversationId) && turns.abortTurn(conversationId)
        telemetry("queue.sendNow", conversationId, "otid" to otid, "abortedCurrent" to aborted)
        // An aborted turn drains from its own terminal; without one, drain now.
        if (!aborted) drainLocked(conversationId)
        true
    }

    /** Runs the head now if it can; otherwise retries briefly off the lock. */
    private suspend fun drainLocked(conversationId: String) {
        if (!drainOnceLocked(conversationId)) scheduleDrain(conversationId)
    }

    private fun scheduleDrain(conversationId: String) {
        synchronized(drainLock) {
            if (drains[conversationId]?.isActive == true) return
            val job = scope.launch { drainWithRetry(conversationId) }
            drains[conversationId] = job
            job.invokeOnCompletion {
                synchronized(drainLock) { if (drains[conversationId] === job) drains.remove(conversationId) }
            }
        }
    }

    private suspend fun drainWithRetry(conversationId: String) {
        repeat(retryAttempts) {
            if (turns.serialized { drainOnceLocked(conversationId) }) return
            delay(retryDelayMs.milliseconds)
        }
        telemetry("queue.drainDeferred", conversationId, "attempts" to retryAttempts)
    }

    /** True when the drain is finished (dispatched, or nothing runnable); false to retry. */
    private suspend fun drainOnceLocked(conversationId: String): Boolean {
        if (turns.hasActiveTurn(conversationId)) return false
        val next = queue.takeNext(conversationId) ?: return true
        if (turns.dispatch(next)) {
            telemetry("queue.dispatched", conversationId, "otid" to next.otid)
            return true
        }
        queue.putBack(next)
        return false
    }

    private fun telemetry(event: String, conversationId: String, vararg attrs: Pair<String, Any?>) {
        Telemetry.event(
            TELEMETRY_TAG, event,
            "conversationId" to conversationId,
            "depth" to queue.queueFor(conversationId).items.size,
            *attrs,
        )
    }

    private companion object {
        const val TELEMETRY_TAG = "ChatSendQueue"
        const val DRAIN_RETRY_DELAY_MS = 50L
        const val DRAIN_RETRY_ATTEMPTS = 40
    }
}
