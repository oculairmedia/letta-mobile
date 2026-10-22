package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An App Server client for the native admin handler tests: answers the agent and conversation reads, records each call. */
internal class FakeNativeClient(
    var failNative: Boolean = false,
    // Extra conversation ids the native conversation.list returns in addition
    // to conv-1 — used to prove P3.4 cross-conversation scope filtering.
    val extraConversationIds: List<String> = emptyList(),
    // letta-mobile-pu7j7: roster size for agent_list; the fake honors the
    // numeric `limit` in the command query exactly like lc-local-backend
    // (and, like it, has no offset concept).
    val agentRosterSize: Int = 1,
    var updateSucceeds: Boolean = true,
    // letta-mobile-fxoew.9: owning agent per listed conversation id.
    val conversationAgentIds: Map<String, String> = emptyMap(),
) : AppServerClient {
    var lastAgentListQuery: kotlinx.serialization.json.JsonObject? = null
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
    val calls = mutableListOf<String>()

    private fun <T> record(op: String, value: T): T {
        calls += op
        if (failNative) error("native down")
        return value
    }

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")

    override suspend fun input(command: AppServerCommand.Input) = error("unused")

    override suspend fun sync(command: AppServerCommand.Sync) = error("unused")

    override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")

    override suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse {
        lastAgentListQuery = command.query
        // Mirrors the real App Server, which honours the limit only when it is a
        // JSON *number* (`typeof body.limit === "number" ? body.limit : 20`) and
        // silently falls back to a 20-row page otherwise. Rejecting string-encoded
        // limits here is deliberate: the previous string-tolerant fake accepted
        // exactly what the backend rejects, which is why letta-mobile-f2ap5
        // (queryOf emitting "50" instead of 50) shipped undetected.
        val limit = (command.query?.get("limit") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toIntOrNull()
            ?: 20
        return record(
            "agent_list",
            AppServerInboundFrame.AgentListResponse(
                requestId = command.requestId,
                success = true,
                agents = buildJsonArray {
                    (1..minOf(limit, agentRosterSize)).forEach { n ->
                        add(buildJsonObject { put("id", "agent-$n"); put("name", "A$n") })
                    }
                },
            ),
        )
    }

    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) = record(
        "agent_retrieve",
        AppServerInboundFrame.AgentRetrieveResponse(
            requestId = command.requestId,
            success = true,
            agent = buildJsonObject { put("id", command.agentId) },
        ),
    )

    override suspend fun agentCreate(command: AppServerCommand.AgentCreate) = record(
        "agent_create",
        AppServerInboundFrame.AgentCreateResponse(
            requestId = command.requestId,
            success = true,
            agent = buildJsonObject { put("id", "agent-new") },
        ),
    )

    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate) = record(
        "agent_update",
        AppServerInboundFrame.AgentUpdateResponse(
            requestId = command.requestId,
            success = updateSucceeds,
            agent = buildJsonObject { put("id", command.agentId) }.takeIf { updateSucceeds },
        ),
    )

    override suspend fun agentDelete(command: AppServerCommand.AgentDelete) = record(
        "agent_delete",
        AppServerInboundFrame.AgentDeleteResponse(requestId = command.requestId, success = true),
    )

    override suspend fun conversationList(command: AppServerCommand.ConversationList) = record(
        "conversation_list",
        AppServerInboundFrame.ConversationListResponse(
            requestId = command.requestId,
            success = true,
            conversations = buildJsonArray {
                (listOf("conv-1") + extraConversationIds).forEach { convId ->
                    add(
                        buildJsonObject {
                            put("id", convId)
                            conversationAgentIds[convId]?.let { put("agent_id", it) }
                        },
                    )
                }
            },
        ),
    )

    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) = record(
        "conversation_retrieve",
        AppServerInboundFrame.ConversationRetrieveResponse(
            requestId = command.requestId,
            success = true,
            conversation = buildJsonObject { put("id", command.conversationId) },
        ),
    )

    override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate) = record(
        "conversation_create",
        AppServerInboundFrame.ConversationCreateResponse(
            requestId = command.requestId,
            success = true,
            conversation = buildJsonObject { put("id", "conv-new") },
        ),
    )

    override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate) = record(
        "conversation_update",
        AppServerInboundFrame.ConversationUpdateResponse(
            requestId = command.requestId,
            success = true,
            conversation = buildJsonObject { put("id", command.conversationId) },
        ),
    )

    override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) = record(
        "conversation_messages_list",
        AppServerInboundFrame.ConversationMessagesListResponse(
            requestId = command.requestId,
            success = true,
            messages = JsonArray(emptyList()),
        ),
    )
}
