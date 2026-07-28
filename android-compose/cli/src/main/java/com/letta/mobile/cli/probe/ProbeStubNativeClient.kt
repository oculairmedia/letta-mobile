package com.letta.mobile.cli.probe

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Native App Server client surface for hermetic stub admin_rpc.
 *
 * Phase 2+ conversation/message handlers are fail-closed on [AppServerClient];
 * the stub has no live Letta App Server, so this projects [ProbeStubStore] into
 * the same response shapes the production handlers expect.
 */
class ProbeStubNativeClient(
    private val store: ProbeStubStore,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = emptyFlow()

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
        error("ProbeStubNativeClient does not own runtime_start")

    override suspend fun input(command: AppServerCommand.Input) =
        error("ProbeStubNativeClient does not own input")

    override suspend fun sync(command: AppServerCommand.Sync) =
        AppServerInboundFrame.SyncResponse(
            requestId = command.requestId ?: "stub-sync",
            runtime = command.runtime,
            success = true,
        )

    override suspend fun abort(command: AppServerCommand.AbortMessage) =
        error("ProbeStubNativeClient does not own abort")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc) =
        error("ProbeStubNativeClient does not own admin_rpc")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
        error("ProbeStubNativeClient does not own external tool responses")

    override suspend fun conversationList(
        command: AppServerCommand.ConversationList,
    ): AppServerInboundFrame.ConversationListResponse =
        AppServerInboundFrame.ConversationListResponse(
            requestId = command.requestId,
            success = true,
            conversations = buildJsonArray {
                store.conversationIds().forEach { id ->
                    add(buildJsonObject { put("id", id) })
                }
            },
        )

    override suspend fun conversationMessagesList(
        command: AppServerCommand.ConversationMessagesList,
    ): AppServerInboundFrame.ConversationMessagesListResponse {
        val query = command.query
        val limit = query.intParam("limit") ?: 50
        val after = query.stringParam("after")
        val before = query.stringParam("before")
        val order = query.stringParam("order") ?: "asc"
        val messages = store.listMessages(command.conversationId, Int.MAX_VALUE, after = null)
        val window = when {
            before != null -> {
                val idx = messages.indexOfFirst { it.id == before }
                if (idx <= 0) emptyList() else messages.take(idx)
            }
            after != null -> {
                val idx = messages.indexOfFirst { it.id == after }
                if (idx < 0) emptyList() else messages.drop(idx + 1)
            }
            else -> messages
        }
        val ordered = if (order == "desc") window.asReversed() else window
        val page = ordered.take(limit)
        return AppServerInboundFrame.ConversationMessagesListResponse(
            requestId = command.requestId,
            success = true,
            messages = buildJsonArray {
                page.forEach { message ->
                    add(
                        buildJsonObject {
                            put("id", message.id)
                            put("conversation_id", message.conversationId)
                            put("message_type", message.messageType)
                            put("content", message.content)
                        },
                    )
                }
            },
        )
    }

    private fun JsonObject?.stringParam(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.intParam(key: String): Int? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}
