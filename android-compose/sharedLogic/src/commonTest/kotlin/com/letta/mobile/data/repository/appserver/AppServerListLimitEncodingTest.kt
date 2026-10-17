package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.runtime.TurnEngineTestStreamClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-code 0.34.14's local store reads list `limit` only as a number
 * (`typeof body.limit === "number" ? body.limit : 20`, letta.js:110688 agents, :109775
 * conversations; :110589 `getListLimit` for messages): a string limit silently pages at 20 (or
 * not at all). Every list query the shared client sends directly to an App Server encodes it as a
 * JSON number.
 */
class AppServerListLimitEncodingTest {
    @Test
    fun theAgentRosterAsksForEveryAgentAsANumber() = runTest {
        val client = QueryRecordingClient()

        DefaultAppServerLocalRepositoryTransport(clientProvider = { client }, requestId = { it }).listAgents()

        assertEquals(JsonPrimitive(10_000), client.queries.single()["limit"])
        assertEquals(JsonPrimitive(0), client.queries.single()["offset"])
    }

    @Test
    fun conversationAndMessageListsSendNumericLimits() = runTest {
        val client = QueryRecordingClient()
        val gateway = AppServerLocalAdminGateway(client) { it }

        gateway.listConversations(limit = 200, archiveStatus = null)
        gateway.listConversationMessages("conv-1", limit = 50, after = null, order = "desc")
        gateway.listConversationMessagesBefore("conv-1", limit = 30, before = "msg-9", order = "desc")

        assertEquals(listOf(200, 50, 30).map(::JsonPrimitive), client.queries.map { it["limit"] })
    }

    private class QueryRecordingClient : TurnEngineTestStreamClient() {
        val queries = mutableListOf<JsonObject>()

        override suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse {
            queries += command.query ?: JsonObject(emptyMap())
            return AppServerInboundFrame.AgentListResponse(command.requestId, success = true, agents = JsonArray(emptyList()))
        }

        override suspend fun conversationList(
            command: AppServerCommand.ConversationList,
        ): AppServerInboundFrame.ConversationListResponse {
            queries += command.query ?: JsonObject(emptyMap())
            return AppServerInboundFrame.ConversationListResponse(
                command.requestId,
                success = true,
                conversations = JsonArray(emptyList()),
            )
        }

        override suspend fun conversationMessagesList(
            command: AppServerCommand.ConversationMessagesList,
        ): AppServerInboundFrame.ConversationMessagesListResponse {
            queries += command.query ?: JsonObject(emptyMap())
            return AppServerInboundFrame.ConversationMessagesListResponse(
                command.requestId,
                success = true,
                messages = JsonArray(emptyList()),
            )
        }
    }
}
