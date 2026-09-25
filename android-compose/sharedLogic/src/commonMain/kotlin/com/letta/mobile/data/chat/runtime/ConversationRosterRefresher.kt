package com.letta.mobile.data.chat.runtime

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-lks7m: keeps a client's conversation roster in step with conversations other
 * clients create.
 *
 * Neither the App Server nor the Iroh node pushes a "conversation created" event, and the node's
 * live-turn fan-out is scoped to the ONE conversation each connection is viewing
 * (`ConversationViewerSubscription`), so stream activity can never reveal a conversation this
 * client has not opened. The roster therefore has to be re-read. This owns only WHEN to re-read:
 *
 *  - [requestRefresh] asks for one; requests landing within [debounce] (and any that arrive while
 *    a refresh is running) coalesce into a single follow-up refresh, so bursts never fan out into a
 *    request storm.
 *  - While [start]ed and active (see [setActive]), a request is made every [interval].
 *  - Becoming active (window focus, app foreground) requests one immediately, so returning to the
 *    app shows what changed while it was in the background without waiting a full interval.
 *
 * What a refresh DOES is the host's [refresh] callback, which must merge rather than reload (see
 * [ChatSessionReducer.conversationRosterRefreshed]). A failing refresh is reported and swallowed:
 * the next tick simply tries again.
 */
class ConversationRosterRefresher(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val active = MutableStateFlow(true)
    private var worker: Job? = null
    private var ticker: Job? = null

    val isRunning: Boolean get() = worker?.isActive == true

    /** Begins serving requests and periodic ticks. Idempotent. */
    fun start() {
        if (isRunning) return
        drainPending()
        worker = scope.launch { serveRequests() }
        ticker = scope.launch { tick() }
    }

    /** Stops serving; a refresh in flight is cancelled and pending requests are dropped. */
    fun stop() {
        worker?.cancel()
        ticker?.cancel()
        worker = null
        ticker = null
        drainPending()
    }

    /**
     * Whether the host is in front of the user. Inactive pauses the periodic tick (requests still
     * work); going from inactive to active requests a refresh straight away.
     */
    fun setActive(isActive: Boolean) {
        val wasActive = active.value
        active.value = isActive
        if (isActive && !wasActive) requestRefresh()
    }

    /** Asks for a roster re-read; coalesced with any other request inside the debounce window. */
    fun requestRefresh() {
        requests.trySend(Unit)
    }

    private suspend fun serveRequests() {
        while (true) {
            requests.receive()
            delay(debounce)
            // Everything that arrived during the debounce is satisfied by this one refresh.
            drainPending()
            runRefresh()
        }
    }

    private suspend fun tick() {
        while (true) {
            active.first { it }
            delay(interval)
            if (active.value) requestRefresh()
        }
    }

    private suspend fun runRefresh() {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Telemetry.error(TAG, "refresh.failed", t)
        }
    }

    private fun drainPending() {
        while (requests.tryReceive().isSuccess) Unit
    }

    companion object {
        private const val TAG = "ConversationRosterRefresher"
        val DEFAULT_DEBOUNCE: Duration = 500.milliseconds
        val DEFAULT_INTERVAL: Duration = 30.seconds
    }
}
