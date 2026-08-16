package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean

class DesktopLocalBackendAdminGatewayTest {
    @Test
    fun `create conversation uses authoritative App Server identity for subsequent reads`() = runTest {
        val client = FakeAppServerClient(
            createResponse = AppServerInboundFrame.ConversationCreateResponse(
                requestId = "response-id",
                success = true,
                conversation = buildJsonObject {
                    put("id", "conversation-authoritative")
                    put("agent_id", "agent-1")
                    put("agent_name", "Meridian")
                    put("summary", "Fresh thread")
                },
            ),
        )
        val gateway = DesktopLocalBackendAdminGateway(client)

        val created = gateway.createConversation("agent-1", "Fresh thread")

        assertEquals("conversation-authoritative", created.id.value)
        assertEquals("agent-1", client.createCommand?.body?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("Fresh thread", client.createCommand?.body?.get("summary")?.jsonPrimitive?.content)
        client.retrieveConversation = created
        assertEquals(created, gateway.getConversation(created.id.value))
        client.listedConversations = listOf(created)
        assertEquals(listOf(created), gateway.listConversations(limit = 40, archiveStatus = null))
    }

    @Test
    fun `create conversation fails closed when App Server rejects mutation`() = runTest {
        val gateway = DesktopLocalBackendAdminGateway(
            FakeAppServerClient(
                AppServerInboundFrame.ConversationCreateResponse(
                    requestId = "response-id",
                    success = false,
                    error = "writer unavailable",
                ),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            gateway.createConversation("agent-1", null)
        }

        assertEquals("writer unavailable", failure.message)
        assertEquals(emptyList(), gateway.listConversations(limit = 40, archiveStatus = null))
    }

    @Test
    fun `first local conversation keeps server identity through send and fresh gateway reload`() = runTest {
        val client = FakeAppServerClient(
            AppServerInboundFrame.ConversationCreateResponse(
                requestId = "response-id",
                success = true,
                conversation = buildJsonObject {
                    put("id", "conversation-persisted")
                    put("agent_id", "agent-1")
                },
            ),
        )
        val firstAdmin = DesktopLocalBackendAdminGateway(client)
        val created = firstAdmin.createConversation("agent-1", null)
        val turnEngine = RecordingTurnEngine()
        val hybrid = DesktopHybridAppServerChatGateway(
            turnEngine = turnEngine,
            client = client,
            adminGateway = firstAdmin,
        )

        hybrid.sendConversationMessage(created.id.value, userRequest("hello")).toList()

        assertEquals("conversation-persisted", turnEngine.command?.conversationId?.value)
        val reloaded = DesktopLocalBackendAdminGateway(client)
        assertEquals(listOf(created), reloaded.listConversations(limit = 40, archiveStatus = null))
        assertEquals(created, reloaded.getConversation(created.id.value))
        hybrid.close()
    }

    @Test
    fun `successful list with missing payload fails instead of erasing visible conversations`() = runTest {
        val client = FakeAppServerClient(failedCreateResponse()).apply { omitConversationList = true }
        val gateway = DesktopLocalBackendAdminGateway(client)

        assertFailsWith<IllegalStateException> {
            gateway.listConversations(limit = 40, archiveStatus = null)
        }
    }

    @Test
    fun `delete archives through authoritative conversation update`() = runTest {
        val client = FakeAppServerClient(failedCreateResponse()).apply {
            retrieveConversation = conversation("conversation-1", archived = false)
        }
        val gateway = DesktopLocalBackendAdminGateway(client)

        gateway.deleteConversation("conversation-1")

        assertEquals("conversation-1", client.updateCommand?.conversationId)
        assertEquals(true, client.updateCommand?.body?.get("archived")?.jsonPrimitive?.boolean)
    }

    private fun failedCreateResponse() = AppServerInboundFrame.ConversationCreateResponse(
        requestId = "unused",
        success = false,
        error = "unused",
    )

    private fun conversation(id: String, archived: Boolean) = com.letta.mobile.data.model.Conversation(
        id = com.letta.mobile.data.model.ConversationId(id),
        agentId = com.letta.mobile.data.model.AgentId("agent-1"),
        archived = archived,
    )

    private fun userRequest(text: String): MessageCreateRequest = MessageCreateRequest(
        messages = listOf(
            Json.encodeToJsonElement(
                MessageCreate.serializer(),
                MessageCreate(role = "user", content = JsonPrimitive(text), otid = "otid-1"),
            ),
        ),
    )

    private class RecordingTurnEngine : TurnEngine {
        var command: TurnCommand? = null

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
            this.command = command
            return emptyFlow()
        }
    }

    private class FakeAppServerClient(
        private val createResponse: AppServerInboundFrame.ConversationCreateResponse,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        var createCommand: AppServerCommand.ConversationCreate? = null
        var listedConversations: List<com.letta.mobile.data.model.Conversation> = emptyList()
        var retrieveConversation: com.letta.mobile.data.model.Conversation? = null
        var omitConversationList = false
        var updateCommand: AppServerCommand.ConversationUpdate? = null

        override suspend fun conversationCreate(
            command: AppServerCommand.ConversationCreate,
        ): AppServerInboundFrame.ConversationCreateResponse {
            createCommand = command
            val response = createResponse.copy(requestId = command.requestId)
            response.conversation?.let {
                val created = desktopChatJson.decodeFromJsonElement(
                    com.letta.mobile.data.model.Conversation.serializer(),
                    it,
                )
                listedConversations = listOf(created)
                retrieveConversation = created
            }
            return response
        }

        override suspend fun conversationList(
            command: AppServerCommand.ConversationList,
        ): AppServerInboundFrame.ConversationListResponse = AppServerInboundFrame.ConversationListResponse(
            requestId = command.requestId,
            success = true,
            conversations = if (omitConversationList) null else kotlinx.serialization.json.JsonArray(
                listedConversations.map {
                    desktopChatJson.encodeToJsonElement(com.letta.mobile.data.model.Conversation.serializer(), it)
                },
            ),
        )

        override suspend fun conversationRetrieve(
            command: AppServerCommand.ConversationRetrieve,
        ): AppServerInboundFrame.ConversationRetrieveResponse = AppServerInboundFrame.ConversationRetrieveResponse(
            requestId = command.requestId,
            success = retrieveConversation != null,
            conversation = retrieveConversation?.let {
                desktopChatJson.encodeToJsonElement(com.letta.mobile.data.model.Conversation.serializer(), it).jsonObject
            },
            error = if (retrieveConversation == null) "missing" else null,
        )

        override suspend fun conversationUpdate(
            command: AppServerCommand.ConversationUpdate,
        ): AppServerInboundFrame.ConversationUpdateResponse {
            updateCommand = command
            val current = retrieveConversation ?: com.letta.mobile.data.model.Conversation(
                id = com.letta.mobile.data.model.ConversationId(command.conversationId),
                agentId = com.letta.mobile.data.model.AgentId("agent-1"),
                archived = false,
            )
            val archived = command.body["archived"]?.jsonPrimitive?.boolean ?: current.archived
            val updated = current.copy(archived = archived)
            retrieveConversation = updated
            listedConversations = listOf(updated)
            return AppServerInboundFrame.ConversationUpdateResponse(
                requestId = command.requestId,
                success = true,
                conversation = desktopChatJson.encodeToJsonElement(
                    com.letta.mobile.data.model.Conversation.serializer(),
                    updated,
                ).jsonObject,
            )
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()

        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }
}
