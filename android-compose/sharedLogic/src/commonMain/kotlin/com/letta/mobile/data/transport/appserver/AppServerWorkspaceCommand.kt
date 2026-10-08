package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The agent-workspace commands (letta-mobile-bzvro.24–.26): MemFS browsing, the agent secrets
 * vault and device files. They share one request path, [AppServerClient.workspaceRequest], so
 * each feature adds its commands without growing the client interface.
 *
 * Their responses are deliberately NOT typed [AppServerInboundFrame]s: they decode as
 * [AppServerInboundFrame.Unknown] and the feature that sent the command reads the raw envelope.
 * That keeps them out of the runtime event mapper, which only sees runtime-scoped frames, and
 * means a response never reaches a viewer, the timeline or a turn's external-transport events.
 */
@Serializable
sealed interface AppServerWorkspaceCommand : AppServerCommand {
    val requestId: String

    /** The `type` of the frame(s) that answer this command. */
    val responseType: String

    /** Safe to replay after an ambiguous disconnect (see [AppServerCommandRetryClass]). */
    val isRead: Boolean

    /** The server answers with several frames; the last carries `done: true`. */
    val isStreamed: Boolean get() = false
}

/** Whether [frame] is the last frame of a streamed workspace response: no `done`, or `done: true`. */
fun isFinalWorkspaceFrame(frame: JsonObject): Boolean =
    (frame["done"] as? JsonPrimitive)?.booleanOrNull != false
