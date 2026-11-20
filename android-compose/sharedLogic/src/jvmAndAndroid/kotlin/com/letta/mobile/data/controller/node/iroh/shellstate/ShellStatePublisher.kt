package com.letta.mobile.data.controller.node.iroh.shellstate

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tells every connected client that the shell state changed, as a `shell_state_updated` frame carrying
 * the whole new document, so each one replaces its copy without a read. The sender gets it too; its own
 * reply already settled the document and an equal revision is ignored. A client that was offline
 * misses the push and reads the document on reconnect, so this is never the source of truth. Sending
 * happens on [scope], so a slow connection never holds up the write's reply.
 */
class ShellStatePublisher(
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var sink: (suspend (String) -> Unit)? = null

    /** [target] writes one frame to every connection allowed to read the document (the endpoint's registry). */
    fun attach(target: suspend (String) -> Unit) {
        sink = target
    }

    internal fun publish(snapshot: ShellStateSnapshot) {
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
            Telemetry.event(TAG, "published", "revision" attr snapshot.documentRevision, "count" attr snapshot.entries.size)
        }
    }

    internal fun frame(snapshot: ShellStateSnapshot): String {
        val document = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), snapshot) as JsonObject
        return buildJsonObject {
            put("v", 1)
            put("type", ShellStateWire.FRAME_TYPE)
            put("id", "shell-state-updated-${UUID.randomUUID()}")
            put("ts", clock().toString())
            document.forEach { (name, value) -> put(name, value) }
        }.toString()
    }

    private companion object {
        const val TAG = "ShellStatePush"
    }
}
