package com.letta.mobile.data.context.limit

import com.letta.mobile.data.compaction.CompactionKey
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-joigh: the context-limit change over Iroh and over a direct App Server. */
class ContextLimitRepositoryTest {
    private val onDefault = ContextLimitRequest(AgentId("agent-1"), ConversationId("conv-default-agent-1"), 400_000)
    private val onConversation = ContextLimitRequest(AgentId("agent-1"), ConversationId("conv-7"), 1_000_000)

    // ── Iroh: conversation.context_limit ───────────────────────────────────

    @Test
    fun irohSendsTheBareDefaultAndTheTokensAndDecodesTheResult() = runTest {
        var sent: JsonObject? = null
        val repository = AdminRpcContextLimitRepository(
            AdminRpcInvoker { method, params ->
                assertEquals(ContextLimitRpc.METHOD, method)
                sent = params
                Json.parseToJsonElement("""{"context_window":400000,"applied_to":"agent","output":"Agent max context set to 400,000 tokens."}""")
            },
        )
        val applied = assertIs<ContextLimitOutcome.Applied>(repository.apply(onDefault))
        assertEquals(400_000, applied.result.contextWindow)
        assertEquals(ContextLimitScope.Agent, applied.result.scope)
        assertEquals("default", sent!!.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(400_000, sent!!.getValue("tokens").jsonPrimitive.int)
    }

    @Test
    fun irohHostsWithoutTheRelayAreUnsupportedAndRefusalsCarryLettaCodesText() = runTest {
        fun failing(error: String) = AdminRpcContextLimitRepository(AdminRpcInvoker { _, _ -> throw ModelControlException(error) })
        assertEquals(ContextLimitOutcome.Unsupported, failing("Unknown method: conversation.context_limit").apply(onDefault))
        assertEquals(ContextLimitOutcome.Unsupported, failing("capability_unavailable: this App Server has no /context-limit").apply(onDefault))
        val noAdminRpc = AdminRpcContextLimitRepository(AdminRpcInvoker { _, _ -> error("admin_rpc is not supported by this transport") })
        assertEquals(ContextLimitOutcome.Unsupported, noAdminRpc.apply(onDefault))
        val refused = failing("context_limit_failed: Context window must be at least 30,000 tokens.").apply(onDefault)
        assertEquals("Context window must be at least 30,000 tokens.", assertIs<ContextLimitOutcome.Failed>(refused).message)
    }

    // ── Direct App Server: execute_command context-limit ───────────────────

    @Test
    fun appServerRunsTheSlashCommandOnTheConversation() = runTest {
        val client = FakeClient(output = "Current conversation max context set to 1,000,000 tokens.")
        val outcome = AppServerContextLimitRepository(client = { client }).apply(onConversation)
        val applied = assertIs<ContextLimitOutcome.Applied>(outcome)
        assertEquals(ContextLimitScope.Conversation, applied.result.scope)
        val sent = client.commands.single()
        assertEquals("context-limit", sent.commandId)
        assertEquals("1000000", sent.args)
        assertEquals("conv-7", sent.runtime?.conversationId)
        assertEquals("agent-1", sent.runtime?.agentId)
    }

    @Test
    fun aServerThatListsCommandsWithoutContextLimitIsUnsupportedWithoutSending() = runTest {
        val client = FakeClient(output = "unused")
        val outcome = AppServerContextLimitRepository(client = { client }, supportedCommands = { listOf("compact", "clear") }).apply(onDefault)
        assertEquals(ContextLimitOutcome.Unsupported, outcome)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun unknownCommandAndMissingExecuteCommandAreUnsupported() = runTest {
        val unknown = FakeClient(output = "Unknown command: context-limit", success = false)
        assertEquals(ContextLimitOutcome.Unsupported, AppServerContextLimitRepository(client = { unknown }).apply(onDefault))
        val noCommand = FakeClient(unsupported = true)
        assertEquals(ContextLimitOutcome.Unsupported, AppServerContextLimitRepository(client = { noCommand }).apply(onDefault))
        assertEquals(ContextLimitOutcome.Unsupported, AppServerContextLimitRepository(client = { null }).apply(onDefault))
    }

    @Test
    fun aRefusalKeepsLettaCodesMessageWithoutItsFailedPrefix() = runTest {
        val client = FakeClient(output = "Failed: Context window cannot exceed the model.json default of 200,000 tokens.", success = false)
        val outcome = AppServerContextLimitRepository(client = { client }).apply(onDefault)
        assertEquals(
            "Context window cannot exceed the model.json default of 200,000 tokens.",
            assertIs<ContextLimitOutcome.Failed>(outcome).message,
        )
    }

    @Test
    fun embedded0261StyleCompletionOnSlashCommandEndIsEnough() = runTest {
        val client = FakeClient(hangs = true)
        val running = async { AppServerContextLimitRepository(client = { client }).apply(onConversation) }
        client.started.await()
        client.events.emit(slashEnd("Current conversation max context set to 1,000,000 tokens."))
        assertEquals(1_000_000, assertIs<ContextLimitOutcome.Applied>(running.await()).result.contextWindow)
    }

    // ── Controller ─────────────────────────────────────────────────────────

    @Test
    fun theControllerRemembersTheAppliedLimitForItsModelAndGatesSupport() = runTest {
        val controller = ContextLimitController(AppServerContextLimitRepository(client = { FakeClient(output = "Agent max context set to 400,000 tokens.") }))
        assertNull(controller.supported.value)
        controller.apply(onDefault, modelValue = "anthropic/claude-opus-5-5")
        assertEquals(true, controller.supported.value)
        val applied = controller.applied.value.getValue(CompactionKey("agent-1", "default"))
        assertEquals(AppliedContextLimit(400_000, ContextLimitScope.Agent, "anthropic/claude-opus-5-5"), applied)
        assertTrue(controller.applying.value.isEmpty())

        val unsupported = ContextLimitController(AppServerContextLimitRepository(client = { null }))
        unsupported.apply(onDefault, modelValue = null)
        assertEquals(false, unsupported.supported.value)
        assertTrue(unsupported.applied.value.isEmpty())
    }

    @Test
    fun aSecondChangeWhileOneRunsIsNotSent() = runTest {
        val client = FakeClient(hangs = true)
        val controller = ContextLimitController(AppServerContextLimitRepository(client = { client }))
        val first = async { controller.apply(onConversation, modelValue = null) }
        client.started.await()
        assertNull(controller.apply(onConversation, modelValue = null))
        assertEquals(1, client.commands.size)
        client.events.emit(slashEnd("Current conversation max context set to 1,000,000 tokens."))
        assertIs<ContextLimitOutcome.Applied>(first.await())
    }

    private fun slashEnd(output: String) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", "conv-7"),
            eventSeq = 1,
            emittedAt = "",
            idempotencyKey = "k",
            delta = buildJsonObject {
                put("message_type", "slash_command_end")
                put("command_id", "context-limit")
                put("output", output)
                put("success", true)
            },
        ),
        raw = JsonObject(emptyMap()),
    )

    private class FakeClient(
        private val output: String? = null,
        private val success: Boolean = true,
        private val unsupported: Boolean = false,
        private val hangs: Boolean = false,
    ) : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>()
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()
        val started = CompletableDeferred<Unit>()

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            commands += command
            started.complete(Unit)
            if (unsupported) throw UnsupportedOperationException("execute_command is not supported by this client")
            if (hangs) awaitCancellation()
            return AppServerInboundFrame.ExecuteCommandResponse(command.requestId, success, output = output)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unexpected()
        override suspend fun input(command: AppServerCommand.Input): Unit = unexpected()
        override suspend fun sync(command: AppServerCommand.Sync) = unexpected()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unexpected()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unexpected()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unexpected()

        private fun unexpected(): Nothing = error("Unexpected App Server operation")
    }
}
