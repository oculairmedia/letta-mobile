package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.10 (F10): letta-code 0.33.6 server frames decode and map on a client whose
 * contract baseline is 0.32.10. Nothing here may become a DecodeFailure.
 */
class LettaCode033CompatibilityTest {
    private fun decode(json: String) = AppServerProtocol.decodeFrame(json.trimIndent())

    @Test
    fun anAgentFreeScopeDecodesInsteadOfFailingTheFrame() {
        val delta = assertIs<AppServerInboundFrame.StreamDelta>(decode(LettaCode0336Frames.AGENT_FREE_STREAM_DELTA).frame)
        assertTrue(delta.runtime.isAgentFree)
        assertEquals(AppServerRuntimeScope.AGENT_FREE, delta.runtime.agentId)
        assertEquals("conv-free", delta.runtime.conversationId)
    }

    @Test
    fun anAbsentAgentIdIsAgentFreeToo() {
        val finished = assertIs<AppServerInboundFrame.TurnFinished>(decode(LettaCode0336Frames.AGENT_FREE_TURN_FINISHED).frame)
        assertTrue(finished.runtime.isAgentFree)
        assertEquals("end_turn", finished.stopReason)
    }

    @Test
    fun anAgentScopeIsUnchanged() {
        val status = assertIs<AppServerInboundFrame.UpdateLoopStatus>(decode(LettaCode0336Frames.LOOP_STATUS_RETRYING).frame)
        assertFalse(status.runtime.isAgentFree)
        assertEquals(AppServerRuntimeScope("agent-1", "conv-1"), status.runtime)
        assertEquals(listOf("cm-1"), status.loopStatus.clientMessageIdsByRunId["run-2"])
    }

    @Test
    fun anAgentFreeScopeEncodesWithoutAnAgentId() {
        val encoded = AppServerProtocol.json.encodeToString(
            AppServerRuntimeScope.serializer(),
            AppServerRuntimeScope(AppServerRuntimeScope.AGENT_FREE, "conv-free"),
        )
        assertEquals("""{"conversation_id":"conv-free"}""", encoded)
        val agent = AppServerProtocol.json.encodeToString(AppServerRuntimeScope.serializer(), AppServerRuntimeScope("a", "c"))
        assertEquals("""{"agent_id":"a","conversation_id":"c"}""", agent)
    }

    @Test
    fun queueRemovalsAndParkedItemsDecode() {
        val queue = assertIs<AppServerInboundFrame.UpdateQueue>(decode(LettaCode0336Frames.QUEUE_WITH_REMOVALS).frame)
        assertEquals(listOf("dequeued", "cancelled"), queue.removed.map { it.disposition })
        assertEquals(listOf("cm-1", "cm-0"), queue.removed.map { it.clientMessageId })
        assertTrue(queue.paused)
        assertEquals("q-1", queue.items.single().id)
    }

    @Test
    fun deviceStatusWithNewToolsetsAndProcessKindsDecodes() {
        val status = assertIs<AppServerInboundFrame.UpdateDeviceStatus>(decode(LettaCode0336Frames.DEVICE_STATUS_TOOLSETS).frame)
        assertEquals("codex", status.deviceStatus["current_toolset"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun aFrameTypeNewIn033IsUnknownNotAFailure() {
        val frame = decode(LettaCode0336Frames.EXECUTE_COMMAND_RESPONSE).frame
        val unknown = assertIs<AppServerInboundFrame.Unknown>(frame)
        assertEquals("execute_command_response", unknown.type)
        assertEquals("req-9", unknown.requestId)
    }

    @Test
    fun approvalClassificationIsTyped() {
        val payloads = map(LettaCode0336Frames.APPROVAL_CLASSIFICATION_END)
        assertIs<RuntimeEventPayload.RemoteStreamFrame>(payloads[0])
        val classified = assertIs<RuntimeEventPayload.ApprovalClassified>(payloads[1])
        assertEquals(listOf("tc-1", "tc-2"), classified.autoAllowedToolCallIds)
        assertEquals(listOf("tc-3"), classified.autoDeniedToolCallIds)
        assertEquals(emptyList(), classified.userInputToolCallIds)
    }

    @Test
    fun retryCarriesThe033Fields() {
        val retry = assertIs<RuntimeEventPayload.RetryNotice>(map(LettaCode0336Frames.RETRY_DELTA)[1])
        assertEquals(2, retry.attempt)
        assertEquals(5, retry.maxAttempts)
        assertEquals(4_000L, retry.delayMs)
        assertEquals("provider_retry", retry.retryKind)
        assertEquals("anthropic", retry.provider)
        assertEquals("overloaded_error", retry.errorCode)
    }

    @Test
    fun everyFixtureDecodesToATypedOrUnknownFrame() {
        listOf(
            LettaCode0336Frames.AGENT_FREE_STREAM_DELTA,
            LettaCode0336Frames.AGENT_FREE_TURN_FINISHED,
            LettaCode0336Frames.LOOP_STATUS_RETRYING,
            LettaCode0336Frames.RETRY_DELTA,
            LettaCode0336Frames.STATUS_DELTA,
            LettaCode0336Frames.SLASH_COMMAND_START,
            LettaCode0336Frames.SLASH_COMMAND_END,
            LettaCode0336Frames.COMMAND_END_DIM,
            LettaCode0336Frames.APPROVAL_CLASSIFICATION_END,
            LettaCode0336Frames.QUEUE_WITH_REMOVALS,
            LettaCode0336Frames.DEVICE_STATUS_TOOLSETS,
            LettaCode0336Frames.EXECUTE_COMMAND_RESPONSE,
        ).forEach { json ->
            val frame = decode(json).frame
            assertFalse(frame is AppServerInboundFrame.DecodeFailure, "decode failed: ${(frame as? AppServerInboundFrame.DecodeFailure)?.diagnostic}")
        }
    }

    private fun map(json: String): List<RuntimeEventPayload> =
        AppServerRuntimeEventMapper().map(command, decode(json)).map { it.payload }

    private val command = TurnCommand(
        backendId = BackendId("backend-1"),
        runtimeId = RuntimeId("runtime-1"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
    )
}
