package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.compaction.CompactCommandOutput
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-57cta: the host's `conversation.compact` relay. */
class ConversationCompactHandlersTest {
    private val params = buildJsonObject { put("agent_id", "agent-1") }

    @Test
    fun compactRunsTheCompactCommandOnTheConversationAndReportsItsCounts() = runTest {
        val client = CompactingClient(commandOutput = COMPLETED)
        val response = dispatch(client, buildJsonObject {
            put("agent_id", "agent-1")
            put("conversation_id", "conv-7")
            put("mode", "sliding_window")
        })

        assertEquals(true, response.getValue("success").jsonPrimitive.boolean, "$response")
        val result = response.getValue("result").jsonObject
        assertEquals("execute_command", result.getValue("path").jsonPrimitive.content)
        assertEquals(48, result.getValue("num_messages_before").jsonPrimitive.int)
        assertEquals(12, result.getValue("num_messages_after").jsonPrimitive.int)
        assertEquals("They set up the repo.", result.getValue("summary").jsonPrimitive.content)
        assertEquals(false, result["no_change"]?.jsonPrimitive?.boolean ?: false)
        val sent = client.commands.single()
        assertEquals("compact", sent.commandId)
        assertEquals("sliding_window", sent.args)
        assertEquals("agent-1", sent.runtime?.agentId)
        assertEquals("conv-7", sent.runtime?.conversationId)
        assertTrue(client.compacts.isEmpty(), "never compacts twice")
    }

    @Test
    fun anUnchangedTranscriptIsReportedAsNoChange() = runTest {
        val client = CompactingClient(commandOutput = "Compaction run, but the number of messages is the same")
        val result = dispatch(client, params).getValue("result").jsonObject
        assertEquals(true, result.getValue("no_change").jsonPrimitive.boolean)
    }

    @Test
    fun anAppServerWithoutTheCommandFallsBackToConversationCompactWithTheAgentForDefault() = runTest {
        val client = CompactingClient(commandOutput = "Unknown command: compact", commandSuccess = false)
        val result = dispatch(client, params).getValue("result").jsonObject
        assertEquals("conversation_compact", result.getValue("path").jsonPrimitive.content)
        assertEquals(30, result.getValue("num_messages_before").jsonPrimitive.int)
        val sent = client.compacts.single()
        assertEquals("default", sent.conversationId)
        assertEquals("agent-1", sent.body?.get("agent_id")?.jsonPrimitive?.content)
    }

    @Test
    fun aClientWithoutExecuteCommandAlsoFallsBack() = runTest {
        val client = CompactingClient(commandUnsupported = true)
        val result = dispatch(client, params).getValue("result").jsonObject
        assertEquals("conversation_compact", result.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun aFailedCompactionIsReportedAndNeverRetriedThroughTheOtherPath() = runTest {
        val client = CompactingClient(commandOutput = "Compact blocked: hook said no", commandSuccess = false)
        val response = dispatch(client, params)
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue("compaction_failed" in response.getValue("error").jsonPrimitive.content, "$response")
        assertTrue("hook said no" in response.getValue("error").jsonPrimitive.content)
        assertTrue(client.compacts.isEmpty())
    }

    @Test
    fun aSlowCompactionIsPendingNotUnsupported() = runTest {
        val client = CompactingClient(commandTimesOut = true)
        val error = dispatch(client, params).getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("compaction_pending"), error)
    }

    @Test
    fun anUnknownModeIsRefusedBeforeAnythingIsSent() = runTest {
        val client = CompactingClient(commandOutput = COMPLETED)
        val response = dispatch(client, buildJsonObject {
            put("agent_id", "agent-1")
            put("mode", "self_compact_all")
        })
        assertEquals(false, response.getValue("success").jsonPrimitive.boolean)
        assertTrue("invalid_mode" in response.getValue("error").jsonPrimitive.content)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun withoutANativeClientTheMethodIsUnavailable() = runTest {
        val router = AdminRpcRouter().also { ConversationCompactHandlers.register(it, nativeClient = null, store = null) }
        val response = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("t-1", ConversationCompactRpc.METHOD, params)),
        ).jsonObject
        assertTrue(response.getValue("error").jsonPrimitive.content.startsWith("capability_unavailable"))
    }

    @Test
    fun compactionIsAConversationManagementWrite() {
        assertEquals(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.forAdminMethod(ConversationCompactRpc.METHOD))
        assertTrue(ConversationCompactRpc.METHOD in AdminRpcRegistry.canonicalMethods)
    }

    @Test
    fun withTheStoreTheAnswerCarriesTheTranscriptBeforeAndAfter() = runTest {
        val root = createTempDirectory("compact-store").toFile()
        try {
            LocalBackendFixtureStore.create(root)
            val transcript = File(LocalBackendFixtureStore.conversationDir(root, LocalBackendFixtureStore.AGENT_ID), "messages.jsonl")
            transcript.writeText("""{"id":"m-1","role":"user","content":"${"x".repeat(4000)}"}""" + "\n")
            val client = CompactingClient(commandOutput = COMPLETED) {
                transcript.writeText("""{"id":"m-2","role":"user","content":"${"y".repeat(400)}","metadata":{"compaction":{"summary":"s"}}}""" + "\n")
            }
            val result = assertTranscriptDrop(client, LocalBackendAdminStore(root))
            assertEquals(1_000L, result.getValue("context_tokens_before").jsonPrimitive.long)
            assertEquals(100L, result.getValue("context_tokens_after").jsonPrimitive.long)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun withoutTheStoreThereIsNoEstimate() = runTest {
        val result = dispatch(CompactingClient(commandOutput = COMPLETED), params).getValue("result").jsonObject
        assertNull(result["context_tokens_before"])
    }

    @Test
    fun theOutputParserReadsMultiLineSummaries() {
        val parsed = CompactCommandOutput.parse("Compaction completed (mode: all). Message buffer length reduced from 9 to 2.\n\nSummary: line one\nline two")
        assertEquals("line one\nline two", parsed.summary)
        assertEquals(9, parsed.messagesBefore)
        assertEquals(2, parsed.messagesAfter)
    }

    private suspend fun assertTranscriptDrop(client: CompactingClient, store: LocalBackendAdminStore): JsonObject {
        val router = AdminRpcRouter().also { ConversationCompactHandlers.register(it, client, store) }
        val response = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("t-1", ConversationCompactRpc.METHOD, buildJsonObject { put("agent_id", LocalBackendFixtureStore.AGENT_ID) })),
        ).jsonObject
        assertEquals(true, response.getValue("success").jsonPrimitive.boolean, "$response")
        return response.getValue("result").jsonObject
    }

    private suspend fun dispatch(client: AppServerClient, params: JsonObject): JsonObject {
        val router = AdminRpcRouter().also { ConversationCompactHandlers.register(it, client, store = null) }
        return Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("t-1", ConversationCompactRpc.METHOD, params))).jsonObject
    }

    private class CompactingClient(
        private val commandOutput: String? = null,
        private val commandSuccess: Boolean = true,
        private val commandUnsupported: Boolean = false,
        private val commandTimesOut: Boolean = false,
        private val onCompact: () -> Unit = {},
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()
        val compacts = mutableListOf<AppServerCommand.ConversationCompact>()

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            commands += command
            if (commandUnsupported) throw UnsupportedOperationException("execute_command is not supported by this client")
            if (commandTimesOut) throw AppServerRequestTimeoutException(command.requestId, 30_000L, RuntimeException("slow"))
            onCompact()
            return AppServerInboundFrame.ExecuteCommandResponse(requestId = command.requestId, success = commandSuccess, output = commandOutput)
        }

        override suspend fun conversationCompact(command: AppServerCommand.ConversationCompact): AppServerInboundFrame.ConversationCompactResponse {
            compacts += command
            return AppServerInboundFrame.ConversationCompactResponse(
                requestId = command.requestId,
                success = true,
                compaction = buildJsonObject {
                    put("num_messages_before", 30)
                    put("num_messages_after", 8)
                    put("summary", "s")
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

    private companion object {
        const val COMPLETED = "Compaction completed (mode: sliding_window). Message buffer length reduced from 48 to 12.\n\nSummary: They set up the repo."
    }
}
