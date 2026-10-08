package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.branch.IrohConversationForkRpc
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationFork
import com.letta.mobile.data.transport.appserver.AppServerConversationForkBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-bzvro.15 (F15): the host side of `conversation.fork`. Sends App Server
 * `conversation_fork` with the options in its body (`agent_id`, `message_id`), then reads the
 * fork back, because upstream answers with the new id only and clients need the conversation.
 */
internal object ConversationForkHandler {
    suspend fun fork(nativeClient: AppServerClient?, sourceId: String, params: JsonObject?): JsonElement {
        val forkedId = NativeAdmin.require(nativeClient, NativeAdminOp.ConversationFork) { c ->
            val response = c.conversationFork(
                AppServerConversationFork(
                    requestId = NativeAdmin.requestId(),
                    conversationId = sourceId,
                    body = AppServerConversationForkBody(
                        agentId = param(params, AdminParamKey(IrohConversationForkRpc.AGENT_ID)),
                        messageId = param(params, AdminParamKey(IrohConversationForkRpc.MESSAGE_ID)),
                    ),
                ),
            )
            if (response.success) response.conversationId else adminError(response.error ?: "conversation_fork failed")
        }
        return NativeAdmin.require(nativeClient, NativeAdminOp.ConversationGet) { c ->
            val response = c.conversationRetrieve(
                AppServerCommand.ConversationRetrieve(requestId = NativeAdmin.requestId(), conversationId = forkedId),
            )
            if (response.success) response.conversation else null
        }
    }
}
