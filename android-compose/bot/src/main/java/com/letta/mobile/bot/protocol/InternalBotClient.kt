package com.letta.mobile.bot.protocol

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class InternalBotClient @Inject constructor(
    private val gateway: BotGateway,
    private val configStore: BotConfigStore,
) : BotClient {
    override suspend fun sendMessage(request: BotChatRequest): BotChatResponse {
        val message = request.toChannelMessage()
        val response = gateway.routeMessage(message, request.conversationId)
        return BotChatResponse(
            response = response.text,
            conversationId = response.conversationId,
            agentId = response.agentId,
        )
    }

    override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> {
        val message = request.toChannelMessage()
        // Forward the explicit fresh-chat contract. A null conversation id
        // alone can resume a prior active gateway/SDK session; forceNew tells
        // the WS transport to request a genuinely new conversation.
        return gateway.streamMessage(message, request.conversationId, request.forceNew).map { chunk ->
            BotStreamChunk(
                text = chunk.text,
                conversationId = chunk.conversationId ?: request.conversationId,
                agentId = message.targetAgentId,
                event = chunk.event,
                toolName = chunk.toolName,
                toolCallId = chunk.toolCallId,
                toolInput = chunk.toolInput,
                isError = chunk.isError,
                done = chunk.isComplete,
            )
        }
    }

    override suspend fun getStatus(): BotStatusResponse {
        // letta-mobile-w2hx.4: gateway sessions are now keyed on
        // `config.id`, not agent ID. The "agents" field on the status
        // response now reports configured agents — heartbeat target if
        // set, falling back to the config ID — rather than transport
        // session keys, which would be meaningless to API callers.
        val sessions = gateway.sessions.value
        val enabledConfigs = configStore.getAll().filter { it.enabled }
        val agentDetails = sessions.map { (configId, session) ->
            BotAgentInfo(
                id = configId,
                name = session.displayName,
                status = session.status.value.name.lowercase(),
            )
        }

        return BotStatusResponse(
            status = gateway.status.value.name.lowercase(),
            agents = enabledConfigs.mapNotNull { it.heartbeatAgentId }.distinct(),
            sessionCount = sessions.size,
            agentDetails = agentDetails,
            activeProfileIds = enabledConfigs.mapNotNull { it.serverProfileId }.distinct(),
            activeModes = enabledConfigs.map { it.mode.name.lowercase() }.distinct(),
        )
    }

    override suspend fun listAgents(): List<BotAgentInfo> = gateway.sessions.value.map { (configId, session) ->
        BotAgentInfo(
            id = configId,
            name = session.displayName,
            status = session.status.value.name.lowercase(),
        )
    }

    private fun BotChatRequest.toChannelMessage(): ChannelMessage = ChannelMessage(
        messageId = "bot-client-${System.currentTimeMillis()}",
        channelId = channelId ?: "api",
        chatId = chatId ?: "api",
        senderId = senderId ?: "api_user",
        senderName = senderName,
        text = message,
        targetAgentId = agentId,
    )
}
