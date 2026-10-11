package com.letta.mobile.data.compaction

import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-3kble: compaction by capability, over Iroh and over a direct App Server. */
class CompactionRepositoryTest {
    private val request = CompactionRequest(AgentId("agent-1"), ConversationId("conv-default-agent-1"), CompactionMode.All)

    // ── Iroh: conversation.compact ─────────────────────────────────────────

    @Test
    fun irohSendsTheBareDefaultAndDecodesTheResult() = runTest {
        var sent: JsonObject? = null
        val repository = AdminRpcCompactionRepository(
            AdminRpcInvoker { method, params ->
                assertEquals(ConversationCompactRpc.METHOD, method)
                sent = params
                Json.parseToJsonElement(
                    """{"path":"execute_command","num_messages_before":48,"num_messages_after":12,"summary":"s",
                       "context_tokens_before":150000,"context_tokens_after":20000}""",
                )
            },
        )
        val outcome = assertIs<CompactionOutcome.Compacted>(repository.compact(request))
        assertEquals(12, outcome.result.messagesAfter)
        assertEquals(20_000L, outcome.result.contextTokensAfter)
        assertEquals("default", sent!!.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("all", sent!!.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun irohMapsTheHostsAnswers() = runTest {
        fun failing(error: String) = AdminRpcCompactionRepository(AdminRpcInvoker { _, _ -> throw ModelControlException(error) })
        assertEquals(CompactionOutcome.Unsupported, failing("Unknown method: conversation.compact").compact(request))
        assertEquals(CompactionOutcome.Unsupported, failing("capability_unavailable: 'conversation.compact' has no injected").compact(request))
        assertEquals(CompactionOutcome.Pending, failing("compaction_pending: the App Server is still compacting").compact(request))
        assertEquals(CompactionOutcome.Failed("Compact blocked: hook"), failing("compaction_failed: Compact blocked: hook").compact(request))
        val noAdminRpc = AdminRpcCompactionRepository(AdminRpcInvoker { _, _ -> error("admin_rpc is not supported by this transport") })
        assertEquals(CompactionOutcome.Unsupported, noAdminRpc.compact(request))
        val unchanged = AdminRpcCompactionRepository(AdminRpcInvoker { _, _ -> Json.parseToJsonElement("""{"path":"execute_command","no_change":true}""") })
        assertIs<CompactionOutcome.AlreadyCompact>(unchanged.compact(request))
    }

    // ── direct App Server ──────────────────────────────────────────────────

    @Test
    fun theCompactCommandIsUsedWhenTheServerListsIt() = runTest {
        val client = FakeClient(commandOutput = "Compaction completed. Message buffer length reduced from 9 to 3.\n\nSummary: ok")
        val repository = AppServerCompactionRepository({ client }, supportedCommands = { listOf("compact", "doctor") })
        val outcome = assertIs<CompactionOutcome.Compacted>(repository.compact(request))
        assertEquals(3, outcome.result.messagesAfter)
        val sent = client.commands.single()
        assertEquals("compact", sent.commandId)
        assertEquals(AppServerRuntimeScope("agent-1", "default"), sent.runtime)
        assertTrue(client.compacts.isEmpty())
    }

    @Test
    fun embedded0261CompletesOnSlashCommandEndWithoutAResponseFrame() = runTest {
        val client = FakeClient(commandHangs = true)
        val repository = AppServerCompactionRepository({ client }, supportedCommands = { listOf("compact") })
        val running = async { repository.compact(request) }
        client.started.await()
        client.events.emit(slashEnd(conversation = "other", output = "not ours"))
        client.events.emit(slashEnd(conversation = "default", output = "Compaction completed. Message buffer length reduced from 20 to 5."))
        val outcome = assertIs<CompactionOutcome.Compacted>(running.await())
        assertEquals(20, outcome.result.messagesBefore)
    }

    @Test
    fun withoutTheCommandConversationCompactCarriesTheAgentForDefault() = runTest {
        val client = FakeClient()
        val repository = AppServerCompactionRepository({ client }, supportedCommands = { listOf("doctor") })
        assertIs<CompactionOutcome.Compacted>(repository.compact(request))
        assertTrue(client.commands.isEmpty())
        val sent = client.compacts.single()
        assertEquals("default", sent.conversationId)
        assertEquals("agent-1", sent.body?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("all", sent.body?.get("compaction_settings")?.jsonObject?.get("mode")?.jsonPrimitive?.content)
    }

    @Test
    fun anUnknownCommandFallsBackAndAServerWithNeitherIsUnsupported() = runTest {
        val unknown = FakeClient(commandOutput = "Unknown command: compact", commandSuccess = false)
        assertIs<CompactionOutcome.Compacted>(AppServerCompactionRepository({ unknown }).compact(request))
        val neither = FakeClient(commandUnsupported = true, compactUnsupported = true)
        assertEquals(CompactionOutcome.Unsupported, AppServerCompactionRepository({ neither }).compact(request))
        assertEquals(CompactionOutcome.Unsupported, AppServerCompactionRepository({ null }).compact(request))
    }

    @Test
    fun aRealFailureIsNotRetriedAndATimeoutIsPending() = runTest {
        val blocked = FakeClient(commandOutput = "Compact blocked: hook", commandSuccess = false)
        assertEquals(CompactionOutcome.Failed("Compact blocked: hook"), AppServerCompactionRepository({ blocked }).compact(request))
        assertTrue(blocked.compacts.isEmpty())
        val slow = FakeClient(commandTimesOut = true)
        assertEquals(CompactionOutcome.Pending, AppServerCompactionRepository({ slow }).compact(request))
    }

    // ── controller ─────────────────────────────────────────────────────────

    @Test
    fun aSecondTapWhileCompactingIsBusy() = runTest {
        val gate = CompletableDeferred<CompactionOutcome>()
        val controller = CompactionController(object : CompactionRepository {
            override suspend fun compact(request: CompactionRequest) = gate.await()
        })
        val first = async { controller.compact(request) }
        testScheduler.runCurrent()
        assertEquals(setOf(CompactionKey("agent-1", "default")), controller.compacting.value)
        assertEquals(CompactionOutcome.Busy, controller.compact(request))
        gate.complete(CompactionOutcome.Pending)
        assertEquals(CompactionOutcome.Pending, first.await())
        assertTrue(controller.compacting.value.isEmpty())
    }

    @Test
    fun anUnsupportedBackendHidesTheButton() = runTest {
        val controller = CompactionController(object : CompactionRepository {
            override suspend fun compact(request: CompactionRequest) = CompactionOutcome.Unsupported
        })
        assertNull(controller.supported.value)
        controller.compact(request)
        assertEquals(false, controller.supported.value)
    }

    @Test
    fun theHostsEstimateCorrectsTheStaleTotal() = runTest {
        val readings = ContextTokenReadings()
        readings.record(ServerFrame.UsageStatistics(agentId = "agent-1", conversationId = "default", contextTokens = 160_000))
        val result = ConversationCompactResult(path = "execute_command", messagesBefore = 48, messagesAfter = 12, contextTokensBefore = 150_000, contextTokensAfter = 20_000)
        val controller = CompactionController(fixed(CompactionOutcome.Compacted(result)), readings)
        controller.compact(request)
        assertEquals(30_000, readings.latest("agent-1", "conv-default-agent-1"))
        assertTrue(readings.isEstimated("agent-1", "default"))
    }

    @Test
    fun withoutAnEstimateTheTotalIsOnlyMarkedStale() = runTest {
        val readings = ContextTokenReadings()
        readings.record(ServerFrame.UsageStatistics(agentId = "agent-1", conversationId = "default", contextTokens = 160_000))
        val controller = CompactionController(fixed(CompactionOutcome.Compacted(ConversationCompactResult(path = "conversation_compact"))), readings)
        controller.compact(request)
        assertEquals(160_000, readings.latest("agent-1", "default"))
        assertTrue(readings.isEstimated("agent-1", "default"))

        val untouched = ContextTokenReadings()
        CompactionController(fixed(CompactionOutcome.Pending), untouched).compact(request)
        assertFalse(untouched.isEstimated("agent-1", "default"))
    }

    private fun fixed(outcome: CompactionOutcome) = object : CompactionRepository {
        override suspend fun compact(request: CompactionRequest) = outcome
    }

    private fun slashEnd(conversation: String, output: String) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", conversation),
            eventSeq = 1,
            emittedAt = "",
            idempotencyKey = "k-$conversation",
            delta = buildJsonObject {
                put("message_type", "slash_command_end")
                put("command_id", "compact")
                put("input", "/compact")
                put("output", output)
                put("success", true)
            },
        ),
        raw = JsonObject(emptyMap()),
    )

    private class FakeClient(
        private val commandOutput: String? = null,
        private val commandSuccess: Boolean = true,
        private val commandHangs: Boolean = false,
        private val commandUnsupported: Boolean = false,
        private val commandTimesOut: Boolean = false,
        private val compactUnsupported: Boolean = false,
    ) : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>()
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()
        val compacts = mutableListOf<AppServerCommand.ConversationCompact>()
        val started = CompletableDeferred<Unit>()

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            commands += command
            started.complete(Unit)
            if (commandUnsupported) throw UnsupportedOperationException("execute_command is not supported by this client")
            if (commandTimesOut) throw AppServerRequestTimeoutException(command.requestId, 30_000L, RuntimeException("slow"))
            if (commandHangs) awaitCancellation()
            return AppServerInboundFrame.ExecuteCommandResponse(command.requestId, commandSuccess, output = commandOutput)
        }

        override suspend fun conversationCompact(command: AppServerCommand.ConversationCompact): AppServerInboundFrame.ConversationCompactResponse {
            if (compactUnsupported) throw UnsupportedOperationException("conversation_compact is not supported by this client")
            compacts += command
            return AppServerInboundFrame.ConversationCompactResponse(
                command.requestId,
                success = true,
                compaction = buildJsonObject {
                    put("num_messages_before", 30)
                    put("num_messages_after", 8)
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
