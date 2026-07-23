package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.7 acceptance: runtime-native operations serve entirely from the
 * native App Server client with the shim (8291) unavailable — the proxy base
 * below points at an unreachable port, so any shim fallback fails loudly.
 */
class NativeAdminHandlersTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null

    @BeforeTest
    fun pinUnreachableShim() {
        // The shim base points at a discard port, but AdminProxyClient's shared
        // defaultTransportFactory is mutable process-wide and other tests in the
        // suite leave a fake installed. Pin a deterministic always-failing
        // transport so "shim unavailable" holds regardless of test order.
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, _, _ -> error("shim unavailable (d6e8g test harness)") }
        }
    }

    @AfterTest
    fun restoreShimFactory() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
    }

    private class FakeNativeClient(
        var failNative: Boolean = false,
    ) : AppServerClient {
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

        override suspend fun agentList(command: AppServerCommand.AgentList) = record(
            "agent_list",
            AppServerInboundFrame.AgentListResponse(
                requestId = command.requestId,
                success = true,
                agents = buildJsonArray { add(buildJsonObject { put("id", "agent-1"); put("name", "A") }) },
            ),
        )

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
                success = true,
                agent = buildJsonObject { put("id", command.agentId) },
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
                conversations = buildJsonArray { add(buildJsonObject { put("id", "conv-1") }) },
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

    private fun router(client: AppServerClient?): AdminRpcRouter {
        val r = AdminRpcRouter()
        // 127.0.0.1:9 (discard) — the shim is UNAVAILABLE in these tests.
        AgentAdminHandlers.register(r, "http://127.0.0.1:9", controller = null, nativeClient = client)
        ConversationAdminHandlers.register(r, "http://127.0.0.1:9", nativeClient = client)
        return r
    }

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t-1",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun nativeOperationsServeWithShimUnavailable() = runTest {
        val client = FakeNativeClient()
        val r = router(client)

        val cases = mapOf(
            "agent.list" to emptyMap(),
            "agent.get" to mapOf("agent_id" to "agent-1"),
            "agent.create" to mapOf("name" to "N"),
            "agent.update" to mapOf("agent_id" to "agent-1", "name" to "N2"),
            "agent.delete" to mapOf("agent_id" to "agent-1"),
            "conversation.list" to emptyMap(),
            "conversation.get" to mapOf("conversation_id" to "conv-1"),
            "conversation.create" to mapOf("agent_id" to "agent-1"),
            "conversation.update" to mapOf("conversation_id" to "conv-1", "summary" to "s"),
            "conversation.archive" to mapOf("conversation_id" to "conv-1"),
            "conversation.restore" to mapOf("conversation_id" to "conv-1"),
            "message.list" to mapOf("conversation_id" to "conv-1"),
        )
        cases.forEach { (method, p) ->
            val response = dispatch(r, method, p)
            assertTrue(response.contains("\"success\":true"), "$method must serve natively: $response")
        }
        assertTrue("agent_list" in client.calls)
        assertTrue("conversation_messages_list" in client.calls)
    }

    @Test
    fun nativeFailureFallsBackToShimPathInsteadOfMisreportingSuccess() = runTest {
        val client = FakeNativeClient(failNative = true)
        val r = router(client)

        // Shim is unreachable too, so the fallback surfaces a transport error —
        // proving the handler attempted the fallback rather than fabricating
        // success from the failed native call.
        val response = dispatch(r, "agent.list", emptyMap())
        assertTrue(response.contains("\"success\":false"))
        assertTrue("agent_list" in client.calls, "native path must be attempted first")
    }

    @Test
    fun withoutANativeClientHandlersStillUseTheShimPath() = runTest {
        val r = router(client = null)
        val response = dispatch(r, "agent.list", emptyMap())
        // Unreachable shim -> failure, but no native calls were possible.
        assertTrue(response.contains("\"success\":false"))
        assertFalse(response.contains("native"))
    }

    @Test
    fun archiveAndRestoreMapToConversationUpdateNatively() = runTest {
        val client = FakeNativeClient()
        val r = router(client)
        dispatch(r, "conversation.archive", mapOf("conversation_id" to "conv-1"))
        dispatch(r, "conversation.restore", mapOf("conversation_id" to "conv-1"))
        assertEquals(2, client.calls.count { it == "conversation_update" })
    }
}
