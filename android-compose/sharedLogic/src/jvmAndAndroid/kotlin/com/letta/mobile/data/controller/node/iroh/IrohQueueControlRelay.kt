package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException

/**
 * letta-mobile-1n5py / qygvv.9: forwards a client's queue controls (`remove_queue_item`,
 * `resume_queue`) to the App Server behind this node.
 *
 * The node already relays `input_accepted{queued}` and `update_queue` to the client, so the
 * client knows its queue item ids; without this relay its queue controls got "Unknown command
 * type" and its cancelled queued inputs stayed parked on the server. The upstream call gets the
 * controller's own request id; the answer carries the client's.
 */
internal class IrohQueueControlRelay(private val controller: AppServerController) {

    /** Returns the response frame JSON for [frameJson]. Never throws (except cancellation). */
    suspend fun handle(frameJson: String): String {
        val command = runCatching {
            AppServerProtocol.json.decodeFromString(AppServerCommand.serializer(), frameJson)
        }.getOrNull()
        return when (command) {
            is AppServerCommand.RemoveQueueItem -> encode(removeQueueItem(command))
            is AppServerCommand.ResumeQueue -> encode(resumeQueue(command))
            else -> """{"type":"error","message":"Malformed queue control frame"}"""
        }
    }

    private suspend fun removeQueueItem(command: AppServerCommand.RemoveQueueItem): AppServerInboundFrame =
        relay("remove_queue_item", onFailure = {
            AppServerInboundFrame.RemoveQueueItemResponse(requestId = command.requestId, success = false, itemId = command.itemId)
        }) {
            controller.removeQueueItem(command.runtime, command.itemId).copy(requestId = command.requestId)
        }

    private suspend fun resumeQueue(command: AppServerCommand.ResumeQueue): AppServerInboundFrame {
        val requestId = command.requestId.orEmpty()
        return relay("resume_queue", onFailure = { error ->
            AppServerInboundFrame.ResumeQueueResponse(
                requestId = requestId,
                runtime = command.runtime,
                success = false,
                error = error,
            )
        }) {
            controller.resumeQueue(command.runtime).copy(requestId = requestId)
        }
    }

    private suspend fun relay(
        type: String,
        onFailure: (String) -> AppServerInboundFrame,
        call: suspend () -> AppServerInboundFrame,
    ): AppServerInboundFrame = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val message = error.message ?: error::class.simpleName.orEmpty()
        Telemetry.event("IrohNode", "queue_control.failed", "type" to type, "error" to message, level = Telemetry.Level.WARN)
        onFailure(message)
    }

    private fun encode(frame: AppServerInboundFrame): String =
        AppServerProtocol.json.encodeToString(AppServerInboundFrame.serializer(), frame)
}
