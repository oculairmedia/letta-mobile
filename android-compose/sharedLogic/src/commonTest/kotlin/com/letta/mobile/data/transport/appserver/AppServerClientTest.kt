package com.letta.mobile.data.transport.appserver

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AppServerClientTest {
    @Test
    fun runtimeStartSendsTypedCommandAndCorrelatesControlResponse() = runTest {
        val transport = FakeAppServerTransport()
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope, requestTimeoutMs = 1_000)

        val response = backgroundScope.async {
            client.runtimeStart(
                AppServerCommand.RuntimeStart(
                    requestId = "start-1",
                    agentId = "agent-1",
                ),
            )
        }
        runCurrent()

        val sent = assertIs<AppServerCommand.RuntimeStart>(transport.sentControlCommands.first())
        assertEquals("start-1", sent.requestId)
        transport.emitControl(
            runtimeStartResponse(
                requestId = "unrelated",
                runtime = runtime,
            ),
        )
        transport.emitControl(
            runtimeStartResponse(
                requestId = "start-1",
                runtime = runtime,
            ),
        )

        assertEquals(runtime, response.await().runtime)
    }

    @Test
    fun generationIsNotPoisonedWhenTransportStartsDisconnected() = runTest {
        // Regression: the init disconnect-watcher used `dropWhile { it }`, but the
        // real transport's isConnected StateFlow starts `false`, so the watcher
        // fired failAll() at construction and every request threw "generation
        // already failed" — turns never started after the reconnect layer landed.
        val transport = FakeAppServerTransport(initiallyConnected = false)
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope, requestTimeoutMs = 1_000)

        // The socket comes up after construction.
        transport.connectedState.value = true
        val response = backgroundScope.async {
            client.runtimeStart(AppServerCommand.RuntimeStart(requestId = "start-1", agentId = "agent-1"))
        }
        runCurrent()

        assertIs<AppServerCommand.RuntimeStart>(transport.sentControlCommands.first())
        transport.emitControl(runtimeStartResponse(requestId = "start-1", runtime = runtime))

        // With the bug this threw IllegalStateException("generation already failed").
        assertEquals(runtime, response.await().runtime)
    }

    @Test
    fun syncAndAbortRequireRequestIdsForCorrelation() = runTest {
        val client = DefaultAppServerClient(FakeAppServerTransport(), requestTimeoutMs = 10)

        assertFailsWith<IllegalArgumentException> {
            client.sync(AppServerCommand.Sync(runtime = runtime))
        }
        assertFailsWith<IllegalArgumentException> {
            client.abort(AppServerCommand.AbortMessage(runtime = runtime))
        }
    }

    @Test
    fun timeoutCancelsPendingCorrelation() = runTest {
        val client = DefaultAppServerClient(FakeAppServerTransport(), requestTimeoutMs = 10)

        assertFailsWith<AppServerRequestTimeoutException> {
            client.runtimeStart(
                AppServerCommand.RuntimeStart(
                    requestId = "start-timeout",
                    agentId = "agent-1",
                ),
            )
        }
    }

    @Test
    fun duplicateResponseIsIgnoredAfterFirstCompletion() = runTest {
        val transport = FakeAppServerTransport()
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope, requestTimeoutMs = 1_000)
        val response = backgroundScope.async {
            client.sync(
                AppServerCommand.Sync(
                    runtime = runtime,
                    requestId = "sync-1",
                ),
            )
        }
        runCurrent()

        transport.emitControl(syncResponse(requestId = "sync-1", runtime = runtime, success = true))
        transport.emitControl(syncResponse(requestId = "sync-1", runtime = runtime, success = false))

        assertEquals(true, response.await().success)
    }

    @Test
    fun clientMethodsSerializeCommandsToControlOnly() = runTest {
        val transport = FakeAppServerTransport()
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope, requestTimeoutMs = 1_000)

        client.input(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(AppServerInputMessage.userText("hello")),
                ),
            ),
        )
        client.sendExternalToolResponse(
            AppServerCommand.ExternalToolCallResponse(
                requestId = "external-tool-1",
                result = AppServerExternalToolResult(
                    content = listOf(AppServerExternalToolResultContent(type = "text", text = "ok")),
                ),
            ),
        )

        assertIs<AppServerCommand.Input>(transport.sentControlCommands[0])
        assertIs<AppServerCommand.ExternalToolCallResponse>(transport.sentControlCommands[1])
    }

    @Test
    fun eventsMergeControlAndStreamFrames() = runTest {
        val transport = FakeAppServerTransport()
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope, requestTimeoutMs = 1_000)

        client.events.test {
            transport.emitControl(syncResponse(requestId = "sync-1", runtime = runtime))
            transport.emitStream(streamDelta())

            assertIs<AppServerInboundFrame.SyncResponse>(awaitItem().frame)
            assertIs<AppServerInboundFrame.StreamDelta>(awaitItem().frame)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }
}

private class FakeAppServerTransport(initiallyConnected: Boolean = true) : AppServerTransport {
    override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
    // Real transports start Disconnected(false) and flip to true once the socket
    // opens; the interface default flowOf(true) hid the init-watcher poison bug.
    val connectedState = kotlinx.coroutines.flow.MutableStateFlow(initiallyConnected)
    override val isConnected = connectedState
    val sentControlCommands = mutableListOf<AppServerCommand>()

    override suspend fun sendControl(command: AppServerCommand) {
        sentControlCommands += command
    }

    fun emitControl(frame: AppServerInboundFrame) {
        controlFrames.tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Control,
                frame = frame,
                raw = kotlinx.serialization.json.buildJsonObject { },
            ),
        )
    }

    fun emitStream(frame: AppServerInboundFrame) {
        streamFrames.tryEmit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = kotlinx.serialization.json.buildJsonObject { },
            ),
        )
    }
}

private fun runtimeStartResponse(
    requestId: String,
    runtime: AppServerRuntimeScope,
): AppServerInboundFrame.RuntimeStartResponse =
    AppServerInboundFrame.RuntimeStartResponse(
        requestId = requestId,
        success = true,
        runtime = runtime,
        created = AppServerCreatedRuntimeEntities(agent = false, conversation = false),
    )

private fun syncResponse(
    requestId: String,
    runtime: AppServerRuntimeScope,
    success: Boolean = true,
): AppServerInboundFrame.SyncResponse =
    AppServerInboundFrame.SyncResponse(
        requestId = requestId,
        runtime = runtime,
        success = success,
    )

private fun streamDelta(): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = AppServerRuntimeScope("agent-1", "conv-1"),
        eventSeq = 1,
        emittedAt = "2026-06-24T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = kotlinx.serialization.json.buildJsonObject {
            put("message_type", "stop_reason")
        },
    )
