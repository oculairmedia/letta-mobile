package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasAccess
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.model.CanvasId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The host's opaque token for one call into a plugin (wire `context.callId`, echoed as `host.emit`'s `callId`). */
@JvmInline
value class PluginCallId(val value: String) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): PluginCallId = PluginCallId(Uuid.random().toHexString())
    }
}

/**
 * The boards a plugin's emits land on (letta-mobile-cdgpr.16): each agent call into the plugin is
 * registered with [begin] under a fresh [PluginCallId], which the plugin gets as `context.callId`
 * and echoes on `host.emit`. An emit is placed on the conversation canvas of the call it names (or
 * the canvas it names, when that call's caller may open it), so a plugin serving two conversations
 * at once never places one call's elements on the other's board.
 *
 * An emit naming no call is placed for the only call in flight, or, with none in flight, for the
 * last caller (a plugin's background work after its call ended). With two or more calls in flight
 * it is ambiguous and refused (no board) rather than guessed. A call stays known for
 * [RECENT_CALLS] calls after it ends, so a late tagged emit still finds its board.
 */
class CallerBoard(private val backend: HostCanvasBackend) : PluginCanvasAccess {
    private val lock = SynchronizedObject()
    private val inFlight = LinkedHashMap<PluginCallId, HostCanvasCaller>()
    private val ended = LinkedHashMap<PluginCallId, HostCanvasCaller>()
    private var last: HostCanvasCaller? = null

    /** Registers a call by [caller]; answers the token to send as `context.callId`. */
    fun begin(caller: HostCanvasCaller, call: PluginCallId = PluginCallId.random()): PluginCallId = synchronized(lock) {
        inFlight[call] = caller
        last = caller
        call
    }

    /** Ends [call]: it is no longer in flight, but a late emit naming it still finds its board. */
    fun end(call: PluginCallId): Unit = synchronized(lock) {
        val caller = inFlight.remove(call) ?: return@synchronized
        ended[call] = caller
        if (ended.size > RECENT_CALLS) ended.remove(ended.keys.first())
    }

    override suspend fun boardFor(requestedCanvas: String?, call: PluginCallId?): HostCanvasEntry? {
        val who = callerFor(call) ?: return null
        val access = if (requestedCanvas != null) backend.open(who, CanvasId(requestedCanvas)) else backend.ownConversation(who)
        return (access as? HostCanvasAccess.Granted)?.entry
    }

    private fun callerFor(call: PluginCallId?): HostCanvasCaller? = synchronized(lock) {
        when {
            call != null -> inFlight[call] ?: ended[call]
            inFlight.size == 1 -> inFlight.values.single()
            inFlight.isEmpty() -> last
            else -> null
        }
    }

    private companion object {
        /** How many ended calls a late tagged emit can still name. */
        const val RECENT_CALLS = 64
    }
}
