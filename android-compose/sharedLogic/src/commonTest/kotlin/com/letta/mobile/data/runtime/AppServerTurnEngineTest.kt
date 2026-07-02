package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineTest {
    @Test
    fun runTurnStartsRuntimeSendsInputAndCompletesOnStopReason() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Started, started.status)

            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            assertEquals(runtime, input.runtime)
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            assertEquals("local-1", payload.messages.single().clientMessageId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))

            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        assertEquals("runtime-start-1", client.runtimeStartCommands.single().requestId)
    }

    @Test
    fun runTurnPreservesMultimodalContentParts() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)
        val contentParts = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "look")
                },
                buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", "image/png")
                        put("data", "abc123")
                    })
                },
            ),
        )

        engine.runTurn(
            command.copy(input = TurnInput.UserMessage("local-image", "look", contentPartsJson = contentParts.toString())),
        ).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            val content = payload.messages.single().content.jsonArray
            assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("look", content[0].jsonObject["text"]?.jsonPrimitive?.content)
            assertEquals("image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("abc123", content[1].jsonObject["source"]?.jsonObject?.get("data")?.jsonPrimitive?.content)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun runTurnRejectsConcurrentTurnsForSameEngine() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()

        assertFailsWith<IllegalStateException> {
            engine.runTurn(command.copy(input = TurnInput.UserMessage("local-2", "second"))).take(1).collect()
        }

        client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
        first.await()
    }

    @Test
    fun runTurnStartsNewRuntimeWhenAgentOrConversationChanges() = runTest {
        var requestId = 0
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = {
                requestId += 1
                "runtime-start-$requestId"
            },
        )

        engine.runTurn(command).test {
            awaitItem()
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            awaitItem()
            awaitComplete()
        }

        val secondRuntime = AppServerRuntimeScope("agent-2", "conv-2")
        val secondCommand = command.copy(
            agentId = AgentId(secondRuntime.agentId),
            conversationId = ConversationId(secondRuntime.conversationId),
            input = TurnInput.UserMessage("local-2", "second"),
        )

        engine.runTurn(secondCommand).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            assertEquals(secondRuntime, input.runtime)
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-2", runtime = secondRuntime))
            awaitItem()
            awaitComplete()
        }

        assertEquals(
            listOf("agent-1", "agent-2"),
            client.runtimeStartCommands.map { it.agentId },
        )
        assertEquals(
            listOf("runtime-start-1", "runtime-start-2"),
            client.runtimeStartCommands.map { it.requestId },
        )
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
    }
}

private class FakeAppServerClient : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 16)
    val runtimeStartCommands = mutableListOf<AppServerCommand.RuntimeStart>()
    val sentCommands = mutableListOf<AppServerCommand>()

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        AppServerInboundFrame.AuthResponse(command.requestId, success = true)

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
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
        sentCommands += command
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        error("sync is not used by these tests")

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort is not used by these tests")

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
                    put("idempotency_key", "evt-1")
                },
            ),
        )
    }
}

private fun streamDelta(
    messageType: String,
    runId: String,
    runtime: AppServerRuntimeScope = AppServerTurnEngineTest.runtime,
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "2026-06-24T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = buildJsonObject {
            put("message_type", messageType)
            put("run_id", runId)
        },
    )
