package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.branch.IrohConversationForkRpc
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationFork
import com.letta.mobile.data.transport.appserver.AppServerConversationForkResponse
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-bzvro.15 (F15): the host's `conversation.fork` forwards to App Server
 * `conversation_fork` with the options in its body and answers with the full fork.
 */
class ConversationForkHandlerTest {
    @AfterTest
    fun resetBreakers() = NativeAdmin.resetCircuitForTest()

    @Test
    fun forkRoutesToConversationForkAndReturnsTheForkedConversation() = runTest {
        val client = ForkingClient()
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = client)) }
        val params = IrohConversationForkRpc.params(ConversationForkRequest("conv-1", "agent-1", "message-4"))

        val response = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("t-1", IrohConversationForkRpc.METHOD, params)),
        ).jsonObject

        assertEquals(true, response.getValue("success").jsonPrimitive.boolean, "$response")
        val result = response.getValue("result").jsonObject
        assertEquals("conv-fork", result["id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", result["agent_id"]?.jsonPrimitive?.content)
        val sent = client.forks.single()
        assertEquals("conv-1", sent.conversationId)
        assertEquals("message-4", sent.body?.messageId)
        assertEquals("agent-1", sent.body?.agentId)
        assertEquals(listOf("conv-fork"), client.retrieved)
    }

    @Test
    fun aRejectedForkFailsTheRpcWithTheServersReason() = runTest {
        val client = ForkingClient(failWith = "Message not found")
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = client)) }

        val response = Json.parseToJsonElement(
            router.dispatch(
                AdminRpcInvocation("t-2", IrohConversationForkRpc.METHOD, buildJsonObject { put("conversation_id", "conv-1") }),
            ),
        ).jsonObject

        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue("Message not found" in response.getValue("error").jsonPrimitive.content, "$response")
        assertEquals(emptyList(), client.retrieved)
    }

    @Test
    fun forkIsAConversationManagementWrite() {
        assertEquals(
            IrohPeerCapabilities.forAdminMethod("conversation.update"),
            IrohPeerCapabilities.forAdminMethod(IrohConversationForkRpc.METHOD),
        )
        assertEquals(NativeAdminOperationPolicy.MutationAmbiguous, NativeAdminOp.ConversationFork.policy)
    }

    private class ForkingClient(private val failWith: String? = null) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        val forks = mutableListOf<AppServerConversationFork>()
        val retrieved = mutableListOf<String>()

        override suspend fun conversationFork(command: AppServerConversationFork): AppServerConversationForkResponse {
            forks += command
            return AppServerConversationForkResponse(
                requestId = command.requestId,
                success = failWith == null,
                conversationId = "conv-fork".takeIf { failWith == null },
                error = failWith,
            )
        }

        override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve): AppServerInboundFrame.ConversationRetrieveResponse {
            retrieved += command.conversationId
            return AppServerInboundFrame.ConversationRetrieveResponse(
                requestId = command.requestId,
                success = true,
                conversation = buildJsonObject {
                    put("id", command.conversationId)
                    put("agent_id", "agent-1")
                },
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
