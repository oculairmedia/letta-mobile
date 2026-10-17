package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-by4vm.121.18: existing on-device agents follow the device model. */
class OnDeviceModelFollowTest {
    @Test
    fun aDeviceModelSwitchFromE2bToE4bMovesTheAgent() = runTest {
        val client = RecordingClient(agents = listOf(record("agent-bobs", E2B)))

        val plan = OnDeviceModelFollower(client) { it }.follow(E4B)

        assertEquals(listOf(AgentId("agent-bobs")), plan.agents)
        assertEquals(listOf<Pair<String, String?>>("agent-bobs" to E4B), client.agentUpdates)
    }

    @Test
    fun aConversationOverrideNamingAnUnservedModelIsRepointed() = runTest {
        val client = RecordingClient(
            agents = listOf(record("agent-bobs", E4B)),
            conversations = listOf(record("local-conv-2", E2B), record("local-conv-3", null), record("local-conv-4", E4B)),
        )

        val plan = OnDeviceModelFollower(client) { it }.follow(E4B)

        assertTrue(plan.agents.isEmpty(), "an agent already on the served model stays")
        assertEquals(listOf(ConversationId("local-conv-2")), plan.conversations)
        assertEquals(listOf<Pair<String, String?>>("local-conv-2" to E4B), client.conversationUpdates)
    }

    @Test
    fun anAgentOffTheDeviceModelIsUntouched() = runTest {
        val client = RecordingClient(
            agents = listOf(
                record("cloud", "anthropic/claude-sonnet-4"),
                record("lm-studio-server", "lmstudio/qwen3-8b"),
                record("no-model", null),
            ),
            conversations = listOf(record("server-conv", "lmstudio/qwen3-8b")),
        )

        val plan = OnDeviceModelFollower(client) { it }.follow(E4B)

        assertTrue(plan.isEmpty)
        assertTrue(client.agentUpdates.isEmpty() && client.conversationUpdates.isEmpty())
    }

    @Test
    fun onlyOnDeviceBridgeHandlesFollow() {
        assertTrue(OnDeviceModelFollowPolicy.isOnDeviceHandle(E2B))
        assertTrue(OnDeviceModelFollowPolicy.isOnDeviceHandle("lmstudio/gemma-3n-E2B-it.task"))
        assertFalse(OnDeviceModelFollowPolicy.isOnDeviceHandle("lmstudio/qwen3-8b"))
        assertFalse(OnDeviceModelFollowPolicy.isOnDeviceHandle("google/gemma-3n-E2B-it-litert-lm"))
        assertFalse(OnDeviceModelFollowPolicy.isOnDeviceHandle(null))
    }

    @Test
    fun theListsAskForEveryRecordAsNumbers() = runTest {
        val client = RecordingClient()

        OnDeviceModelFollower(client) { it }.follow(E4B)

        assertEquals(JsonPrimitive(1_000), client.agentQuery?.get("limit"))
        assertEquals(JsonPrimitive(1_000), client.conversationQuery?.get("limit"))
    }

    private fun record(id: String, model: String?): JsonObject = buildJsonObject {
        put("id", id)
        model?.let { put("model", it) }
    }

    private class RecordingClient(
        private val agents: List<JsonObject> = emptyList(),
        private val conversations: List<JsonObject> = emptyList(),
    ) : AppServerClient {
        val agentUpdates = mutableListOf<Pair<String, String?>>()
        val conversationUpdates = mutableListOf<Pair<String, String?>>()
        var agentQuery: JsonObject? = null
        var conversationQuery: JsonObject? = null

        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun agentList(command: AppServerCommand.AgentList): AppServerInboundFrame.AgentListResponse {
            agentQuery = command.query
            return AppServerInboundFrame.AgentListResponse(command.requestId, success = true, agents = JsonArray(agents))
        }

        override suspend fun conversationList(
            command: AppServerCommand.ConversationList,
        ): AppServerInboundFrame.ConversationListResponse {
            conversationQuery = command.query
            return AppServerInboundFrame.ConversationListResponse(
                command.requestId,
                success = true,
                conversations = JsonArray(conversations),
            )
        }

        override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate): AppServerInboundFrame.AgentUpdateResponse {
            agentUpdates += command.agentId to (command.body["model"] as? JsonPrimitive)?.content
            return AppServerInboundFrame.AgentUpdateResponse(command.requestId, success = true)
        }

        override suspend fun conversationUpdate(
            command: AppServerCommand.ConversationUpdate,
        ): AppServerInboundFrame.ConversationUpdateResponse {
            conversationUpdates += command.conversationId to (command.body["model"] as? JsonPrimitive)?.content
            return AppServerInboundFrame.ConversationUpdateResponse(command.requestId, success = true)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            error("runtimeStart unused")

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val E2B = "lmstudio/google/gemma-3n-E2B-it-litert-lm"
        const val E4B = "lmstudio/google/gemma-3n-E4B-it-litert-lm"
    }
}
