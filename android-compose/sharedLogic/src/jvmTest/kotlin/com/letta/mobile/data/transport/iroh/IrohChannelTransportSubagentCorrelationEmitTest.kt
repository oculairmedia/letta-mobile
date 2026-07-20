package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CopyOnWriteArrayList

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
/**
 * letta-mobile-m6oa1.3 (consumer wiring): end-to-end proof that the
 * previously WRITE-ONLY [SubagentCorrelator] is now OBSERVABLE. Frames flow
 * through the REAL transport observer path
 * ([IrohChannelTransport.ingestObserverFrame] ->
 * [IrohChannelTransport.correlateAgentFrame]) and the resulting
 * [ServerFrame.SubagentsUpdated] pushes are asserted on the transport's public
 * `events` flow — the exact seam `SubagentRepository.observePushEvents`
 * consumes.
 *
 * The observer stream is driven directly via the handle's `observerStreamFrames`
 * test override, so no QUIC endpoint is dialed. There is NO active local turn,
 * so the dual-ingest guard hands every frame to the observer.
 *
 * Backtick test names MUST NOT contain "()" — Kotlin/Native rejects them
 * (#868). These live in jvmTest, but the convention is kept repo-wide.
 */
class IrohChannelTransportSubagentCorrelationEmitTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private val observerStream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)

    private fun transport(): IrohChannelTransport = IrohChannelTransport(
        scope = clientScope,
        activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
        testDialer = { config ->
            IrohConnectionHandle(
                config = config,
                ticket = "ticket",
                sessionId = "session",
                observerStreamFrames = observerStream,
                close = {},
            )
        },
    )

    private fun agentDispatchDelta(toolCallId: String, description: String): AppServerReceivedFrame =
        streamDelta(
            eventSeq = 1,
            buildJsonObject {
                put("message_type", "tool_call_message")
                put("run_id", "run-parent")
                put(
                    "tool_call",
                    buildJsonObject {
                        put("name", "Agent")
                        put("tool_call_id", toolCallId)
                        put(
                            "arguments",
                            """{"description":"$description","subagent_type":"researcher"}""",
                        )
                    },
                )
            },
        )

    private fun agentReturnDelta(toolCallId: String): AppServerReceivedFrame =
        streamDelta(
            eventSeq = 2,
            buildJsonObject {
                put("message_type", "tool_return_message")
                put("run_id", "run-parent")
                put("tool_call_id", toolCallId)
            },
        )

    private fun streamDelta(eventSeq: Long, delta: JsonElement): AppServerReceivedFrame {
        val frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope(agentId = "agent-parent", conversationId = "conv-parent"),
            eventSeq = eventSeq,
            emittedAt = "2026-07-01T00:00:0${eventSeq}Z",
            idempotencyKey = "evt-$eventSeq",
            delta = delta,
        )
        return AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject {
                put("type", "stream_delta")
                put("idempotency_key", frame.idempotencyKey)
                put("delta", delta.jsonObject)
            },
        )
    }

    @Test
    fun `dispatch emits a SubagentsUpdated with the running entry and full snapshot`() = runBlocking {
        val transport = transport()
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            // Let the observer collector arm against the (test-overridden) stream.
            withTimeout(3.seconds) {
                while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10.milliseconds)
            }
            delay(100.milliseconds)

            assertTrue(observerStream.tryEmit(agentDispatchDelta("tc-1", "scout the repo")))

            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.SubagentsUpdated }) delay(10.milliseconds)
            }
            val updated = frames.filterIsInstance<ServerFrame.SubagentsUpdated>().single()
            assertEquals(IrohChannelTransport.SUBAGENT_REASON_STARTED, updated.reason)
            // The changed entry rides on `subagent`.
            assertEquals("tc-1", updated.subagent?.toolCallId)
            assertEquals("scout the repo", updated.subagent?.description)
            assertEquals(SubagentStatus.RUNNING, updated.subagent?.status)
            // The full snapshot rides on `subagentsActive` — what the repo folds.
            assertEquals(listOf("tc-1"), updated.subagentsActive.map { it.toolCallId })
            assertEquals(SubagentStatus.RUNNING, updated.subagentsActive.single().status)
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun `idempotent re-observe of the same dispatch does not emit a duplicate`() = runBlocking {
        val transport = transport()
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(3.seconds) {
                while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10.milliseconds)
            }
            delay(100.milliseconds)

            assertTrue(observerStream.tryEmit(agentDispatchDelta("tc-1", "scout the repo")))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.SubagentsUpdated }) delay(10.milliseconds)
            }
            // Re-observe the byte-identical dispatch: reducer no-ops, revision
            // gate suppresses any second push.
            assertTrue(observerStream.tryEmit(agentDispatchDelta("tc-1", "scout the repo")))
            // Give any (incorrect) duplicate push time to race in.
            delay(300.milliseconds)

            assertEquals(
                1,
                frames.count { it is ServerFrame.SubagentsUpdated },
                "revision-gated: an idempotent re-observe must not re-emit; got " +
                    frames.filterIsInstance<ServerFrame.SubagentsUpdated>().map { it.reason },
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun `return emits a SubagentsUpdated marking the entry completed`() = runBlocking {
        val transport = transport()
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(3.seconds) {
                while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10.milliseconds)
            }
            delay(100.milliseconds)

            assertTrue(observerStream.tryEmit(agentDispatchDelta("tc-1", "scout the repo")))
            withTimeout(3.seconds) {
                while (frames.none { it is ServerFrame.SubagentsUpdated }) delay(10.milliseconds)
            }
            assertTrue(observerStream.tryEmit(agentReturnDelta("tc-1")))
            withTimeout(3.seconds) {
                while (frames.filterIsInstance<ServerFrame.SubagentsUpdated>().none {
                        it.reason == IrohChannelTransport.SUBAGENT_REASON_COMPLETED
                    }
                ) delay(10.milliseconds)
            }

            val completed = frames.filterIsInstance<ServerFrame.SubagentsUpdated>()
                .single { it.reason == IrohChannelTransport.SUBAGENT_REASON_COMPLETED }
            assertEquals("tc-1", completed.subagent?.toolCallId)
            assertEquals(SubagentStatus.COMPLETED, completed.subagent?.status)
            assertEquals(SubagentStatus.COMPLETED, completed.subagentsActive.single().status)
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun `return for an unknown tool call id emits nothing`() = runBlocking {
        val transport = transport()
        val frames = CopyOnWriteArrayList<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            transport.connect("iroh://ticket", "", "device", "test")
            withTimeout(3.seconds) {
                while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10.milliseconds)
            }
            delay(100.milliseconds)

            // A return whose tool_call_id was never dispatched: the reducer
            // ignores it, revision stays put, no push is produced.
            assertTrue(observerStream.tryEmit(agentReturnDelta("never-dispatched")))
            delay(300.milliseconds)

            assertEquals(
                0,
                frames.count { it is ServerFrame.SubagentsUpdated },
                "an unknown-id return must not synthesize a subagent push",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }
}
