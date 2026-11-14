package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.CanvasMountDecision
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.ViewerHandle
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One committed binding change: the conversation of [agent] now shows [canvas]. */
internal data class CanvasMountChange(val agent: AgentId, val conversation: ConversationId, val canvas: CanvasId)

/**
 * Tells the connections viewing a conversation that its binding changed, as a `canvas_mount_updated` frame, so an
 * open conversation follows a switch made on another device without polling (S2).
 *
 * Each viewer gets the frame only if `canvas.mount.resolve` would have shown it the same canvas: the capabilities its
 * connection established ([ViewerHandle.adminContext]) admit resolve, and the resolve authority built from its own verified identity and
 * scopes allows addressing the binding and seeing the canvas. Viewers that fail any check get nothing, so the push
 * reveals nothing resolve would not. Sending runs on [scope]; the writer's reply never waits for it. A client that
 * misses the push re-resolves when it opens the conversation.
 */
internal class CanvasMountChanges(
    private val sources: CanvasMountSources,
    private val durability: CanvasMountDurability,
    private val scope: CoroutineScope,
) {
    fun publish(change: CanvasMountChange) {
        scope.launch {
            val viewers = runCatchingCancellable { sources.viewers(change.conversation) }.getOrDefault(emptySet())
            val frame = frameOf(change)
            val delivered = viewers.count { viewer -> mayReceive(viewer, change) && viewer.writeFrame(frame) }
            Telemetry.event(CANVAS_MOUNT_TAG, "change.published", "viewers" attr viewers.size, "delivered" attr delivered)
        }
    }

    private suspend fun mayReceive(viewer: ViewerHandle, change: CanvasMountChange): Boolean {
        val context = viewer.adminContext()?.takeIf { it.admits(CanvasMountWire.RESOLVE) } ?: return false
        val connection = CanvasMountConnection.of(context) ?: return false
        val authority = HostCanvasMountAuthority(connection, sources.catalog, sources.directory, CanvasMountCallTrace())
        val request = CanvasMountRequest(connection.principal, CanvasMountScope.Conversation(change.agent, change.conversation))
        val addressed = authority.mayAddress(request) == CanvasMountDecision.ALLOW
        return addressed && authority.maySee(request, change.canvas) == CanvasMountDecision.ALLOW
    }

    /** Whether the capabilities the connection established admit [method], as the admin RPC gate would. */
    private fun AdminRpcRequestContext.admits(method: String): Boolean =
        IrohPeerCapabilities.isAllowed(grantedCapabilities, IrohPeerCapabilities.forAdminMethod(method))

    private fun frameOf(change: CanvasMountChange): String = buildJsonObject {
        put("type", CanvasMountWire.EVENT_TYPE)
        put(CanvasMountWire.AGENT_ID, change.agent.value)
        put(CanvasMountWire.CONVERSATION_ID, change.conversation.value)
        put(CanvasMountWire.CANVAS_ID, change.canvas.value)
        put(CanvasMountWire.DURABILITY, durability.wire)
    }.toString()
}
