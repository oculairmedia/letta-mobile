package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerQueueItem
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerStopReason
import com.letta.mobile.data.transport.appserver.AppServerTurnBoundary
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * letta-mobile-qygvv.6: the last `update_queue` snapshot seen for one runtime.
 * [paused] is true while the server holds items parked by an `abort_message`.
 */
data class AppServerQueueSnapshot(
    val items: List<AppServerQueueItem> = emptyList(),
    val paused: Boolean = false,
)

/**
 * letta-mobile-qygvv.6: a queued input this client sent, removed from the server queue after a
 * user abort. [draft] is the Cancelled lifecycle for that input's turn, so a host can stop showing
 * [clientMessageId] as pending. A lease that is still waiting on the item also settles Cancelled
 * through its own flow, from the `update_queue` removal the server emits.
 */
data class CancelledQueuedInput(
    val key: TurnRuntimeKey,
    val clientMessageId: String,
    val itemId: String,
    val draft: RuntimeEventDraft,
)

internal const val QUEUED_INPUT_REMOVED_AFTER_ABORT_REASON = "Queued input removed after abort"

/**
 * letta-mobile-qygvv.6: queue hygiene after a user abort.
 *
 * `abort_message` pauses the conversation queue on the App Server (0.32.3): queued user items stay
 * parked (`paused: true`) until the next `input` or a `resume_queue`. Once the abort settles
 * (`abort_message_response` with `aborted=true`, or a cancelled `turn_finished` for a runtime
 * with an abort outstanding), this:
 * 1. removes THIS client's queued items for that runtime with `remove_queue_item` and publishes a
 *    [CancelledQueuedInput] for each, then
 * 2. sends `resume_queue` so items from other clients are not left parked.
 *
 * It also keeps the latest queue snapshot per runtime for UI ([snapshots]).
 *
 * "This client's items" are the ones whose `client_message_id` this engine sent ([noteSentInput]).
 * [scope] runs the `turn_finished` path; without it only the abort-response path settles.
 */
internal class AppServerQueueHygiene(
    private val client: AppServerClient,
    private val requestIdFactory: () -> String,
    private val scope: CoroutineScope? = null,
    private val maxTrackedInputs: Int = DEFAULT_MAX_TRACKED_INPUTS,
    /** True when a host router feeds every inbound frame through [observe]. */
    private val routed: Boolean = false,
) {
    private class SentInput(val key: TurnRuntimeKey, val command: TurnCommand)

    private val lock = SynchronizedObject()
    private val sentInputs = LinkedHashMap<String, SentInput>()
    private val pendingAborts = HashMap<TurnRuntimeKey, AppServerRuntimeScope>()

    private val _snapshots = MutableStateFlow<Map<TurnRuntimeKey, AppServerQueueSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<TurnRuntimeKey, AppServerQueueSnapshot>> = _snapshots.asStateFlow()

    private val _cancelledInputs = MutableSharedFlow<CancelledQueuedInput>(extraBufferCapacity = CANCELLED_BUFFER)
    val cancelledInputs: SharedFlow<CancelledQueuedInput> = _cancelledInputs.asSharedFlow()

    /** Records a user input this client sent, so its queue item can be recognised later. */
    fun noteSentInput(key: TurnRuntimeKey, clientMessageId: String?, command: TurnCommand) {
        val id = clientMessageId?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            sentInputs.remove(id)
            sentInputs[id] = SentInput(key, command)
            while (sentInputs.size > maxTrackedInputs) sentInputs.remove(sentInputs.keys.first())
        }
    }

    fun isOwnInput(clientMessageId: String): Boolean = synchronized(lock) { clientMessageId in sentInputs }

    fun noteAbortRequested(runtime: AppServerRuntimeScope) {
        synchronized(lock) { pendingAborts[runtime.key()] = runtime }
    }

    /** Feeds one inbound frame. Idempotent for a frame seen twice. */
    fun observe(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateQueue -> observeQueue(frame)
            is AppServerInboundFrame.TurnFinished -> observeTurnFinished(frame)
            else -> Unit
        }
    }

    /**
     * The abort-response path. `aborted=true` settles the abort now; `success` without `aborted`
     * means nothing was running, so nothing was parked. Anything else leaves the abort pending
     * for a cancelled `turn_finished`.
     */
    suspend fun onAbortResponse(runtime: AppServerRuntimeScope, response: AppServerInboundFrame.AbortMessageResponse) {
        val key = runtime.key()
        when {
            response.success && response.aborted -> if (takePendingAbort(key) != null) settleAfterAbort(runtime)
            response.success -> takePendingAbort(key)
            else -> Unit
        }
    }

    /**
     * A frame a turn's own loop collected. Ignored when [routed]: the host's router already feeds
     * every inbound frame through [observe].
     */
    fun observeTurnFrame(frame: AppServerInboundFrame) {
        if (!routed) observe(frame)
    }

    private fun observeQueue(frame: AppServerInboundFrame.UpdateQueue) {
        val key = frame.runtime.key()
        val next = AppServerQueueSnapshot(items = frame.items, paused = frame.paused)
        var wasPaused = false
        _snapshots.update { current ->
            wasPaused = current[key]?.paused == true
            if (next.items.isEmpty() && !next.paused) current - key else current + (key to next)
        }
        if (next.paused && !wasPaused) {
            Telemetry.event(
                TELEMETRY_TAG, "queue.paused",
                "key" to key.toString(),
                "items" to next.items.size,
                "pausedItems" to next.items.count { it.paused },
                "ownItems" to next.items.count { item -> item.clientMessageId?.let(::isOwnInput) == true },
            )
        }
        // An item that left the queue is no longer ours to clean up.
        val left = frame.removed.mapNotNull { it.clientMessageId }
        if (left.isNotEmpty()) synchronized(lock) { left.forEach(sentInputs::remove) }
    }

    private fun observeTurnFinished(frame: AppServerInboundFrame.TurnFinished) {
        if (AppServerStopReason.boundaryOf(frame.stopReason) != AppServerTurnBoundary.Cancelled) return
        val launcher = scope ?: return
        val runtime = takePendingAbort(frame.runtime.key()) ?: return
        launcher.launch { settleAfterAbort(runtime) }
    }

    private fun takePendingAbort(key: TurnRuntimeKey): AppServerRuntimeScope? =
        synchronized(lock) { pendingAborts.remove(key) }

    private suspend fun settleAfterAbort(runtime: AppServerRuntimeScope) {
        val key = runtime.key()
        val queued = _snapshots.value[key]?.items.orEmpty()
        val own = queued.filter { item -> item.clientMessageId?.let(::isOwnInput) == true }
        val removed = own.count { item -> removeOwnItem(runtime, key, item) }
        // The server parks only items that exist at abort time, so an empty queue has nothing to
        // release. Resume whenever anything else is (or may be) parked.
        if (queued.size > removed) resume(runtime, key)
    }

    /**
     * letta-mobile-qygvv.9: a lease whose input is still waiting in the server queue was
     * cancelled. Take that input off the queue, or the server runs it later with nobody
     * observing. False when the item is not in the last snapshot or the removal failed.
     */
    suspend fun removeQueuedInput(runtime: AppServerRuntimeScope, clientMessageId: String): Boolean {
        val key = runtime.key()
        val item = _snapshots.value[key]?.items?.lastOrNull { it.clientMessageId == clientMessageId }
        if (item == null) {
            Telemetry.event(
                TELEMETRY_TAG, "queue.item_not_found",
                "key" to key.toString(),
                "clientMessageId" to clientMessageId,
                level = Telemetry.Level.WARN,
            )
            return false
        }
        return removeOwnItem(runtime, key, item)
    }

    private suspend fun removeOwnItem(runtime: AppServerRuntimeScope, key: TurnRuntimeKey, item: AppServerQueueItem): Boolean {
        val clientMessageId = item.clientMessageId ?: return false
        val response = callOrNull("remove_queue_item", key) {
            client.removeQueueItem(
                AppServerCommand.RemoveQueueItem(requestId = requestIdFactory(), runtime = runtime, itemId = item.id),
            )
        }
        val success = response?.success == true
        Telemetry.event(
            TELEMETRY_TAG, "queue.item_removed",
            "key" to key.toString(),
            "itemId" to item.id,
            "clientMessageId" to clientMessageId,
            "success" to success,
            level = if (success) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
        if (!success) return false
        val sent = synchronized(lock) { sentInputs.remove(clientMessageId) } ?: return true
        _cancelledInputs.tryEmit(
            CancelledQueuedInput(
                key = key,
                clientMessageId = clientMessageId,
                itemId = item.id,
                draft = sent.command.removedAfterAbortDraft(),
            ),
        )
        return true
    }

    private suspend fun resume(runtime: AppServerRuntimeScope, key: TurnRuntimeKey) {
        val response = callOrNull("resume_queue", key) {
            client.resumeQueue(AppServerCommand.ResumeQueue(runtime = runtime, requestId = requestIdFactory()))
        }
        Telemetry.event(
            TELEMETRY_TAG, "queue.resumed",
            "key" to key.toString(),
            "success" to (response?.success == true),
            "resumed" to response?.resumed,
            "error" to response?.error,
            level = if (response?.success == true) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    /** Hygiene is best effort: a failed or unsupported call must never fail the abort. */
    private suspend fun <T> callOrNull(command: String, key: TurnRuntimeKey, call: suspend () -> T): T? =
        try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Telemetry.event(
                TELEMETRY_TAG, "queue.command_failed",
                "key" to key.toString(),
                "command" to command,
                "error" to (error.message ?: error::class.simpleName),
                level = Telemetry.Level.WARN,
            )
            null
        }

    private fun TurnCommand.removedAfterAbortDraft(): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(
                RuntimeRunStatus.Cancelled,
                reason = QUEUED_INPUT_REMOVED_AFTER_ABORT_REASON,
            ),
        )

    private fun AppServerRuntimeScope.key(): TurnRuntimeKey = TurnRuntimeKey(agentId, conversationId)

    private companion object {
        const val TELEMETRY_TAG = "AppServerQueueHygiene"
        const val DEFAULT_MAX_TRACKED_INPUTS = 256
        const val CANCELLED_BUFFER = 64
    }
}
