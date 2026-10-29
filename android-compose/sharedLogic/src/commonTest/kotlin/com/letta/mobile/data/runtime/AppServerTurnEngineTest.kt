package com.letta.mobile.data.runtime



import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.ToolPolicy
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTest : AppServerTurnEngineFixture() {
    /**
     * letta-mobile-4rxis: letta-code owns context management from the agent's own compaction
     * settings. A user turn goes straight to runtime_start + input; the engine never reads the
     * agent, stamps a context window, lists messages or calls conversation_compact first.
     */
    @Test
    fun userTurnSendsNoContextManagementCommandsBeforeInput() = runTest {
        val order = mutableListOf<String>()
        val client = FakeAppServerClient(onRuntimeStart = { order += "runtime_start" }, onInput = { order += "input" })
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            awaitItem()
            assertEquals(listOf("runtime_start", "input"), order)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun explicitAllowlistPreservesBuiltInsAndIncludesOnlyThisRuntimesHostTools() = runTest {
        val client = FakeAppServerClient()
        val registry = ExternalToolRegistry.hostTools(listOf(DeviceActionTool))
        val engine = AppServerTurnEngine(client = client, externalToolRegistry = registry)
        val restricted = command.copy(
            toolPolicy = ToolPolicy(allowedTools = setOf(ToolName("read_file"))),
        )

        engine.runTurn(restricted).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            val runtimeStart = client.runtimeStartCommands.single()
            assertEquals(
                listOf("device_action"),
                runtimeStart.externalTools!!.flatMap { it.tools }.map { it.name },
                "advertising alone is insufficient; create_message must carry the same host tool",
            )
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            val payload = assertIs<AppServerInputPayload.CreateMessage>(input.payload)
            assertEquals(listOf("device_action", "read_file"), payload.clientToolAllowlist)
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun defaultAllowlistStaysNullAndRegistryLessTurnsDoNotAddHostTools() = runTest {
        val defaultClient = FakeAppServerClient()
        val defaultEngine = AppServerTurnEngine(
            client = defaultClient,
            externalToolRegistry = ExternalToolRegistry.hostTools(listOf(DeviceActionTool)),
        )
        defaultEngine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            val payload = assertIs<AppServerInputPayload.CreateMessage>(
                assertIs<AppServerCommand.Input>(defaultClient.sentCommands.single()).payload,
            )
            assertEquals(null, payload.clientToolAllowlist)
            defaultClient.emit(streamDelta(messageType = "stop_reason", runId = "run-default"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }

        val noRegistryClient = FakeAppServerClient()
        val noRegistryEngine = AppServerTurnEngine(client = noRegistryClient)
        noRegistryEngine.runTurn(command.copy(toolPolicy = ToolPolicy(allowedTools = setOf(ToolName("read_file"))))).test {
            awaitItem()
            val payload = assertIs<AppServerInputPayload.CreateMessage>(
                assertIs<AppServerCommand.Input>(noRegistryClient.sentCommands.single()).payload,
            )
            assertEquals(listOf("read_file"), payload.clientToolAllowlist)
            noRegistryClient.emit(streamDelta(messageType = "stop_reason", runId = "run-no-registry"))
            awaitItem()
            awaitItem()
            awaitComplete()
        }
    }
    companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(
                localMessageId = "local-1",
                text = "hello",
            ),
        )
    }}




abstract class AppServerTurnEngineFixture : com.letta.mobile.util.TelemetryRecordingTest() {
    protected val runtime get() = AppServerTurnEngineTest.runtime
    protected val command get() = AppServerTurnEngineTest.command

    protected object DeviceActionTool : HostExternalTool {
        override val name = "device_action"
        override val description = "Perform an Android device action."
        override val capability = Capability.SlimAgents
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun invoke(
            input: kotlinx.serialization.json.JsonObject,
            agentId: String?,
        ) = com.letta.mobile.data.controller.extras.ExternalToolResult.Success("ok")
    }

}


internal class FakeAppServerClient(
    private val onRuntimeStart: () -> Unit = {},
    private val onInput: () -> Unit = {},
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)
    val runtimeStartCommands = mutableListOf<AppServerCommand.RuntimeStart>()
    val sentCommands = mutableListOf<AppServerCommand>()

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        AppServerInboundFrame.AuthResponse(command.requestId, success = true)

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
        onRuntimeStart()
        runtimeStartCommands += command
        return AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                agentId = requireNotNull(command.agentId),
                conversationId = requireNotNull(command.conversationId),
            ),
        )
    }

    override suspend fun input(command: AppServerCommand.Input) {
        onInput()
        sentCommands += command
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync is not used by these tests")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort is not used by these tests")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
        sentCommands += command
        return AppServerInboundFrame.AdminRpcResponse(
            requestId = command.requestId,
            success = true,
            result = null,
        )
    }

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        sentCommands += command
    }

    fun emit(frame: AppServerInboundFrame) {
        (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {
                    put("type", frame.type ?: "unknown")
                    put("idempotency_key", (frame as? AppServerInboundFrame.StreamDelta)?.idempotencyKey ?: "evt-1")
                    if (frame is AppServerInboundFrame.StreamDelta) {
                        put("delta", frame.delta)
                    }
                },
            ),
        )
    }
}

internal fun streamDelta(
    messageType: String,
    runId: String,
    runtime: AppServerRuntimeScope = AppServerTurnEngineTest.runtime,
    stopReason: String? = null,
    totalTokens: Int? = null,
    toolCallId: String? = null,
    key: String = "evt-$messageType-$runId-${toolCallId.orEmpty()}",
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-06-24T00:00:00Z",
        idempotencyKey = key,
        delta = buildJsonObject {
            put("message_type", messageType)
            put("run_id", runId)
            if (stopReason != null) put("stop_reason", stopReason)
            if (totalTokens != null) put("total_tokens", totalTokens)
            if (toolCallId != null) put("tool_call_id", toolCallId)
        },
    )

internal fun String.jsonDeltaString(key: String): String? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(this).jsonObject
    val value = raw["delta"]?.jsonObject?.get(key)
    value?.jsonPrimitive?.content
}.getOrNull()
