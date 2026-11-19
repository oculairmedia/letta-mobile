package com.letta.mobile.data.controller.node.iroh.pins

import com.letta.mobile.data.pins.shared.AgentPinsSnapshot
import com.letta.mobile.data.pins.shared.AgentPinsWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tells every connected client that the pinned agents changed, as a `pins_updated` frame carrying the
 * whole new list and its revision, so each one replaces its list without a read. The sender gets it
 * too; its own reply already settled the list and an equal revision is ignored. A client that was
 * offline misses the push and reads the list on reconnect, so this is never the source of truth.
 * Sending happens on [scope], so a slow connection never holds up the write's reply.
 */
class AgentPinsPublisher(
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var sink: (suspend (String) -> Unit)? = null

    /** [target] writes one frame to every connection allowed to read agents (the endpoint's registry). */
    fun attach(target: suspend (String) -> Unit) {
        sink = target
    }

    /** Pushes [snapshot] to every connection. */
    fun publish(snapshot: AgentPinsSnapshot) {
        val target = sink ?: return
        val frame = frame(snapshot)
        scope.launch {
            try {
                target(frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedFailure: Exception) {
                Telemetry.event(TAG, "failed", "class" attr expectedFailure::class.simpleName, level = Telemetry.Level.WARN)
                return@launch
            }
            Telemetry.event(TAG, "published", "revision" attr snapshot.revision, "count" attr snapshot.agentIds.size)
        }
    }

    internal fun frame(snapshot: AgentPinsSnapshot): String {
        val at = clock().toString()
        return buildJsonObject {
            put("v", 1)
            put("type", AgentPinsWire.FRAME_TYPE)
            put("id", "pins-updated-${UUID.randomUUID()}")
            put("ts", at)
            put("revision", snapshot.revision)
            put(AgentPinsWire.AGENT_IDS, JsonArray(snapshot.agentIds.map { JsonPrimitive(it.value) }))
        }.toString()
    }

    private companion object {
        const val TAG = "AgentPinsPush"
    }
}
