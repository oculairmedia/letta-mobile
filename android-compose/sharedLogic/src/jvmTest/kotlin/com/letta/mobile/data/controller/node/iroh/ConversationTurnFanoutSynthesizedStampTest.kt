package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-4vtng.1: the deltas the host synthesizes itself (user echo, projected tool call and
 * return, dangling-call settlement) carry the same identity a stream frame does. They never pass
 * through [com.letta.mobile.data.runtime.TurnDraftProcessor], so the fanout stamps them.
 */
class ConversationTurnFanoutSynthesizedStampTest {

    private val runtime = AppServerRuntimeScope("agent-1", "conv-C")

    private class CapturingSink : ViewerFrameSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) { chunks.add(bytes) }
        fun deltas(): List<JsonObject> {
            val decoder = IrohFrameCodec.Decoder(
                IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
                IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES,
            )
            return chunks.flatMap { decoder.feed(it) }
                .map { Json.parseToJsonElement(it).jsonObject.getValue("delta").jsonObject }
        }
    }

    private fun fanoutWith(sink: CapturingSink): ConversationTurnFanout {
        val viewer = IrohViewerHandle(
            connectionId = "conn-init",
            sink = sink,
            eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
            streamWriteMutex = Mutex(),
            frameParts = { false },
            maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
        )
        return ConversationTurnFanout(
            conversationId = "conv-C",
            runtime = runtime,
            viewersFor = { emptySet() },
            initiatorViewer = viewer,
            turnId = "cm-1",
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test
    fun userEchoIsStampedWithTheClientMessageIdAsLogicalAndTurnId() = runTest {
        val sink = CapturingSink()
        fanoutWith(sink).broadcastUserEcho("cm-1", "hello", null)
        val echo = sink.deltas().single()
        assertEquals("cm-1", echo.str("logical_message_id"))
        assertEquals("cm-1", echo.str("turn_id"))
        assertEquals("cm-user-cm-1", echo.str("id"))
    }

    @Test
    fun projectedToolCallAndReturnAreStampedWithTheCallId() = runTest {
        val sink = CapturingSink()
        val fanout = fanoutWith(sink)
        val callId = ToolCallId("call-1")
        fanout.onDraft(RuntimeEventPayload.ToolCallObserved(callId, ToolName("bash"), "{}"))
        fanout.onDraft(RuntimeEventPayload.ToolReturnObserved(callId, ToolExecutionStatus.Succeeded, "ok"))
        val (call, result) = sink.deltas()
        assertEquals("tc-call-1", call.str("logical_message_id"))
        assertEquals("cm-1", call.str("turn_id"))
        assertEquals("tr-call-1", result.str("logical_message_id"))
        assertEquals("cm-1", result.str("turn_id"))
    }

    @Test
    fun danglingCallSettlementResolvesToTheSameReturnId() = runTest {
        val sink = CapturingSink()
        val fanout = fanoutWith(sink)
        fanout.onDraft(RuntimeEventPayload.ToolCallObserved(ToolCallId("call-9"), ToolName("bash"), "{}"))
        fanout.flushOpenToolCalls()
        val settled = sink.deltas().last()
        assertEquals("tool_return_message", settled.str("message_type"))
        assertEquals("tr-call-9", settled.str("logical_message_id"))
    }
}
