package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Relays host device status to current and later viewers of the exact runtime. The wrapper's
 * notification envelope keeps the native event sequence out of the relayed turn's sequence.
 */
internal fun bindToolsetDeviceStatuses(scope: CoroutineScope, client: AppServerClient, viewers: ConnectionRegistry): Job {
    val lock = Mutex()
    var generation: Long? = null
    var linked = false
    val latest = mutableMapOf<AppServerRuntimeScope, JsonObject>()
    viewers.addViewerJoinedListener { conversationId, viewer ->
        lock.withLock { replayToolsetStatus(latest, ConversationId(conversationId), viewer) }
    }
    return scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val monitoring = launch(start = CoroutineStart.UNDISPATCHED) {
            client.isConnected.collect { connected ->
                lock.withLock {
                    linked = connected
                    if (!connected) latest.clear()
                }
            }
        }
        try {
            client.events.collect { received ->
                val status = received.frame as? AppServerInboundFrame.UpdateDeviceStatus ?: return@collect
                val frame = buildJsonObject {
                    put("type", "toolset_status")
                    put("runtime", AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), status.runtime))
                    put("device_status", status.deviceStatus)
                }
                lock.withLock {
                    if (!linked) return@withLock
                    val incoming = received.connectionGeneration
                    if (olderToolsetGeneration(generation, incoming)) return@withLock
                    if (incoming != null && generation != incoming) {
                        latest.clear()
                        generation = incoming
                    }
                    latest[status.runtime] = frame
                    viewers.viewersFor(status.runtime.conversationId).forEach { publishToolsetStatus(it, status.runtime, frame) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                monitoring.cancelAndJoin()
                lock.withLock {
                    linked = false
                    latest.clear()
                }
            }
        }
    }
}

private suspend fun replayToolsetStatus(
    latest: Map<AppServerRuntimeScope, JsonObject>,
    conversationId: ConversationId,
    viewer: ViewerHandle,
) {
    latest.filterKeys { it.conversationId == conversationId.value }.forEach { (runtime, frame) ->
        publishToolsetStatus(viewer, runtime, frame)
    }
}

private suspend fun publishToolsetStatus(viewer: ViewerHandle, runtime: AppServerRuntimeScope, frame: JsonObject) {
    val context = viewer.adminContext() ?: return
    if (!context.canAccessConversation(runtime.conversationId)) return
    if (context.authorizedAgentIds?.let { runtime.agentId !in it } == true) return
    if (!IrohPeerCapabilities.isAllowed(context.grantedCapabilities, IrohPeerCapabilities.CHAT_READ)) return
    viewer.writeFrame(frame.toString())
}

private fun olderToolsetGeneration(current: Long?, incoming: Long?): Boolean {
    if (current == null) return false
    if (incoming == null) return false
    return incoming < current
}
