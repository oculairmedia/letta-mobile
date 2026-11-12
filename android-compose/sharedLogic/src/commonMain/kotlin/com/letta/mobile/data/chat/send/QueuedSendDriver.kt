package com.letta.mobile.data.chat.send

import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
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
    suspend fun dispatch(item: QueuedChatSend, clearComposer: Boolean = false): Boolean
}

/** The queue transitions [QueuedSendDriver] reports. */
private enum class QueueEvent(val wireName: String) {
    Paused("queue.paused"),
    Cancelled("queue.cancelled"),
    Resumed("queue.resumed"),
    SendNow("queue.sendNow"),
    Dispatched("queue.dispatched"),
    DrainDeferred("queue.drainDeferred"),
    DrainRecovered("queue.drainRecovered"),
    HeldByOtherClient("queue.heldByOtherClient"),
    OtherClientReleased("queue.otherClientReleased"),
}

/**
 * Runs the [ChatSendQueue] against the live transport.
 *
 * A message sent while its conversation is busy is queued here instead of failing. The head runs
 * once the running turn is over: after its terminal ([onTurnFinishedLocked]), on [resume], or right
 * after the current turn is aborted to push an item through ([sendNow]). A Stop pauses the queue
 * ([onStopped]), matching the App Server, which parks its own queue on `abort_message`.
 *
 * The transport retires a turn a moment after its terminal frame is published, so a drain that
 * still sees the turn retries briefly instead of dispatching into a busy rejection, then keeps
 * retrying with capped exponential backoff. Readiness signals interrupt the wait without resuming
 * a queue the user paused.
 */
internal class QueuedSendDriver(
    private val scope: CoroutineScope,
    private val queue: ChatSendQueue,
    private val turns: QueuedSendTurns,
    private val retry: DrainRetry = DrainRetry(),
    otherClientBackoff: OtherClientHold.Backoff = OtherClientHold.Backoff(),
) {
    /** Fast retirement retry cadence before capped backoff begins. */
    data class DrainRetry(val delayMs: Long = DRAIN_RETRY_DELAY_MS, val attempts: Int = DRAIN_RETRY_ATTEMPTS)

    private val drainLock = SynchronizedObject()
    private val drains = HashMap<QueueConversationId, Job>()
    private val backoff = OtherClientHold.Backoff(initialMs = DRAIN_BACKOFF_INITIAL_MS)
    private val retryCounts = HashMap<QueueConversationId, Int>()
    private val backingOff = HashSet<QueueConversationId>()
    private val otherClientHold = OtherClientHold(scope, otherClientBackoff) { conversationId ->
        turns.serialized { releaseOtherClientHoldLocked(conversationId) }
    }

    /**
     * One user submission under the lifecycle lock: never pass a waiting head. A null position
     * means immediate acceptance; otherwise the existing queue owns the send and its retries.
     */
    suspend fun submitLocked(item: QueuedChatSend): Int? =
        if (!queue.hasItems(item.conversationId) && turns.dispatch(item, clearComposer = true)) null
        else enqueueLocked(item)

    /** A synchronous user Stop; pause the queue only when the transport accepts the abort. */
    fun stop(conversationId: QueueConversationId): Boolean {
        val accepted = turns.abortTurn(conversationId)
        if (accepted) onStopped(conversationId)
        return accepted
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
     * [item] bounced off another client's turn in its conversation (caller
     * holds the turn lock). It waits at the head for that turn's end, never failing.
     */
    fun holdForOtherClientLocked(item: QueuedChatSend) {
        queue.holdForOtherClient(item)
        report(QueueEvent.HeldByOtherClient, item.conversationId, item.id)
        otherClientHold.schedule(item.conversationId)
    }

    /** A terminal this device does not own: another client's turn may have ended, so try again. */
    suspend fun onForeignTerminalLocked(alreadyDrained: QueueConversationId? = null) {
        queue.state.value.keys.toList().forEach {
            when {
                queue.queueFor(it).heldByOtherClient -> releaseOtherClientHoldLocked(it)
                it == alreadyDrained -> Unit
                else -> drainLocked(it)
            }
        }
    }

    /** Reconnected transport: retry waiting heads now, without releasing a user pause. */
    suspend fun onTransportReadyLocked() = onForeignTerminalLocked()

    private suspend fun releaseOtherClientHoldLocked(conversationId: QueueConversationId) {
        // A foreign terminal can race the transport's retirement, or the next autonomous wake.
        // Keep observing ownership until idle instead of spending the only backstop on a busy drain.
        if (turns.hasActiveTurn(conversationId)) {
            otherClientHold.reset(conversationId)
            otherClientHold.schedule(conversationId)
            return
        }
        otherClientHold.cancel(conversationId)
        if (!queue.releaseOtherClientHold(conversationId)) return
        report(QueueEvent.OtherClientReleased, conversationId)
        drainLocked(conversationId)
    }

    /** A user Stop: hold what is queued. */
    fun onStopped(conversationId: QueueConversationId) {
        cancelDrain(conversationId)
        if (queue.pause(conversationId)) report(QueueEvent.Paused, conversationId)
    }

    /** A terminal disconnect: hold every queue rather than drop what the user typed. */
    fun onDisconnected() {
        queue.pauseAll().forEach {
            cancelDrain(it)
            report(QueueEvent.Paused, it)
        }
    }

    suspend fun cancel(id: QueuedSendId): Boolean = turns.serialized {
        val removed = queue.remove(id) ?: return@serialized false
        if (queue.queueFor(removed.conversationId).items.isEmpty()) {
            cancelDrain(removed.conversationId)
            retryCounts.remove(removed.conversationId)
            backingOff.remove(removed.conversationId)
        }
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

    /** Runs the head now, interrupting any pending backoff on a readiness signal. */
    private suspend fun drainLocked(conversationId: QueueConversationId) {
        cancelDrain(conversationId)
        if (!drainOnceLocked(conversationId)) scheduleDrain(conversationId)
    }

    private fun cancelDrain(conversationId: QueueConversationId) {
        synchronized(drainLock) { drains.remove(conversationId)?.cancel() }
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
        var attempt = 0
        while (true) {
            val waitMs = if (attempt < retry.attempts) retry.delayMs else backoff.delayMs(attempt - retry.attempts)
            delay(waitMs.milliseconds)
            val finished = turns.serialized {
                attempt = (retryCounts[conversationId] ?: 0) + 1
                retryCounts[conversationId] = attempt
                val drained = drainOnceLocked(conversationId)
                if (!drained && attempt >= retry.attempts && backingOff.add(conversationId)) {
                    report(QueueEvent.DrainDeferred, conversationId, attempts = attempt)
                }
                drained
            }
            if (finished) return
        }
    }

    /** True when the drain is finished (dispatched, or nothing runnable); false to retry. */
    private suspend fun drainOnceLocked(conversationId: QueueConversationId): Boolean {
        val waiting = queue.queueFor(conversationId)
        val next = waiting.items.firstOrNull()?.takeUnless { waiting.paused || waiting.heldByOtherClient } ?: return true
        if (turns.hasActiveTurn(conversationId)) return false
        // The queue owns presentation until dispatch has published the transcript row. Keeping
        // the head here also avoids a disappear/reappear pulse when the transport rejects a retry.
        val dispatched = runCatching { turns.dispatch(next) }.getOrElse { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            false
        }
        if (!dispatched) return false
        queue.remove(next.id)
        val attempts = retryCounts.remove(conversationId)
        if (backingOff.remove(conversationId)) {
            report(QueueEvent.DrainRecovered, conversationId, next.id, attempts = attempts)
        }
        report(QueueEvent.Dispatched, conversationId, next.id)
        return true
    }

    private fun report(
        event: QueueEvent,
        conversationId: QueueConversationId,
        id: QueuedSendId? = null,
        attempts: Int? = null,
    ) {
        Telemetry.event(
            TELEMETRY_TAG, event.wireName,
            "conversationId" attr conversationId.value,
            "otid" attr id?.value,
            "depth" attr queue.queueFor(conversationId).items.size,
            "attempts" attr attempts,
        )
    }

    private companion object {
        const val TELEMETRY_TAG = "ChatSendQueue"
        const val DRAIN_BACKOFF_INITIAL_MS = 500L
        const val DRAIN_RETRY_DELAY_MS = 50L
        const val DRAIN_RETRY_ATTEMPTS = 40
    }
}
