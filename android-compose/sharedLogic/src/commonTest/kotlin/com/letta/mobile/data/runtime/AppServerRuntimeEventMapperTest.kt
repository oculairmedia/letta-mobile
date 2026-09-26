package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
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
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppServerRuntimeEventMapperTest {
    private val mapper = AppServerRuntimeEventMapper()

    @Test
    fun mapsStopReasonToCompletedLifecycle() {
        val drafts = mapper.map(
            command = command,
            received = received(streamDelta(messageType = "stop_reason", runId = "run-1")),
        )

        val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts[0].payload)
        assertEquals("stop_reason", stop.messageType)
        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload)
        assertEquals(RuntimeRunStatus.Completed, payload.status)
        assertEquals("run-1", drafts[1].runId?.value)
    }

    @Test
    fun mapsCancelledStopReasonToCancelledLifecycle() {
        val drafts = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "stop_reason",
                    runId = "run-1",
                    body = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", "run-1")
                        put("stop_reason", "cancelled")
                        put("message", "App Server loop idle after abort")
                    },
                ),
            ),
        )

        val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts[0].payload)
        assertEquals("stop_reason", stop.messageType)
        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload)
        assertEquals(RuntimeRunStatus.Cancelled, payload.status)
        assertEquals("run-1", drafts[1].runId?.value)
        // letta-mobile-qygvv.28: the Iroh node relays a cancel's reason as the stop_reason's message.
        assertEquals("App Server loop idle after abort", payload.reason)
    }

    @Test
    fun mapsErrorStopReasonToFailedLifecycleWithReason() {
        val drafts = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "stop_reason",
                    runId = "run-1",
                    body = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", "run-1")
                        put("stop_reason", "error")
                        put("message", "provider timed out")
                    },
                ),
            ),
        )

        val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts[0].payload)
        assertEquals("stop_reason", stop.messageType)
        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload)
        assertEquals(RuntimeRunStatus.Failed, payload.status)
        assertEquals("provider timed out", payload.reason)
        assertEquals("run-1", drafts[1].runId?.value)
    }

    @Test
    fun mapsErrorStopReasonWithoutMessageToFailedLifecycleWithFallbackReason() {
        val drafts = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "stop_reason",
                    runId = "run-1",
                    body = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", "run-1")
                        put("stop_reason", "error")
                    },
                ),
            ),
        )

        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload)
        assertEquals(RuntimeRunStatus.Failed, payload.status)
        assertEquals("App Server turn stopped with error", payload.reason)
    }

    @Test
    fun mapsToolUseStopReasonToRemoteFrameSoTurnContinues() {
        val draft = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "stop_reason",
                    runId = "run-1",
                    body = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", "run-1")
                        put("stop_reason", "tool_use")
                    },
                ),
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.RemoteStreamFrame>(draft.payload)
        assertEquals("stop_reason", payload.messageType)
        assertEquals("run-1", draft.runId?.value)
    }

    @Test
    fun mapsLengthStopReasonToCompletedLifecycleLikeMaxTokens() {
        // letta-mobile-xm1dk: OpenAI-compat providers emit `length`, not `max_tokens`.
        val drafts = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "stop_reason",
                    runId = "run-1",
                    body = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", "run-1")
                        put("stop_reason", "length")
                    },
                ),
            ),
        )

        val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts[0].payload)
        assertEquals("stop_reason", stop.messageType)
        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload)
        assertEquals(RuntimeRunStatus.Completed, payload.status)
        assertEquals("run-1", drafts[1].runId?.value)
    }

    @Test
    fun mapsLoopErrorToFailedLifecycle() {
        val draft = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "loop_error",
                    body = buildJsonObject {
                        put("message_type", "loop_error")
                        put("run_id", "run-1")
                        put("message", "provider failed")
                    },
                ),
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.RunLifecycleChanged>(draft.payload)
        assertEquals(RuntimeRunStatus.Failed, payload.status)
        assertEquals("provider failed", payload.reason)
    }

    @Test
    fun mapsUnknownStreamDeltaToRemoteStreamFrameWithoutCrashing() {
        val draft = mapper.map(
            command = command,
            received = received(streamDelta(messageType = "future_delta")),
        ).single()

        val payload = assertIs<RuntimeEventPayload.RemoteStreamFrame>(draft.payload)
        assertEquals("future_delta", payload.messageType)
        assertEquals("evt-1", payload.frameId)
    }

    @Test
    fun mapsExternalToolCallRequestToToolCallObserved() {
        val draft = mapper.map(
            command = command,
            received = received(
                AppServerInboundFrame.ExternalToolCallRequest(
                    requestId = "external-1",
                    runtime = runtime,
                    toolCallId = "tool-call-1",
                    toolName = "mobile_echo",
                    input = buildJsonObject { put("text", "hello") },
                ),
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.ToolCallObserved>(draft.payload)
        assertEquals("tool-call-1", payload.toolCallId.value)
        assertEquals("mobile_echo", payload.toolName.value)
        assertEquals("""{"text":"hello"}""", payload.argumentsJson)
    }

    @Test
    fun mapsClientToolEndDeltaToToolReturnObserved() {
        val draft = mapper.map(
            command = command,
            received = received(
                streamDelta(
                    messageType = "client_tool_end",
                    body = buildJsonObject {
                        put("message_type", "client_tool_end")
                        put("tool_call_id", "tool-call-1")
                        put("status", "success")
                        put("output", "tool output")
                    },
                ),
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.ToolReturnObserved>(draft.payload)
        assertEquals("tool-call-1", payload.toolCallId.value)
        assertEquals("tool output", payload.body)
    }

    @Test
    fun mapsControlRequestToApprovalRequested() {
        val draft = mapper.map(
            command = command,
            received = received(
                AppServerInboundFrame.ControlRequest(
                    requestId = "approval-1",
                    request = buildJsonObject {
                        put("subtype", "can_use_tool")
                        put("tool_name", "write_file")
                        put("tool_call_id", "tool-call-1")
                        put(
                            "input",
                            buildJsonObject {
                                put("path", "README.md")
                            },
                        )
                    },
                    agentId = "agent-1",
                    conversationId = "conv-1",
                ),
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.ApprovalRequested>(draft.payload)
        assertEquals("approval-1", payload.request.approvalId.value)
        assertEquals("tool-call-1", payload.request.callId.value)
        assertEquals("write_file", payload.request.toolName.value)
        assertEquals("""{"path":"README.md"}""", payload.request.argumentsPreview)
    }

    @Test
    fun preservesUnknownFramesAsExternalTransportPayload() {
        val raw = buildJsonObject {
            put("type", "future_frame")
            put("request_id", "future-1")
        }
        val draft = mapper.map(
            command = command,
            received = AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = AppServerInboundFrame.Unknown(type = "future_frame", raw = raw),
                raw = raw,
            ),
        ).single()

        val payload = assertIs<RuntimeEventPayload.ExternalTransportFrame>(draft.payload)
        assertEquals("future-1", payload.transportMessageId)
        assertEquals(raw.toString(), payload.body)
    }

    @Test
    fun requiresApprovalStopEmitsTheFrameButNoLifecycle() {
        val drafts = mapper.map(command, received(stopWith("requires_approval")))

        assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts.single().payload)
    }

    @Test
    fun officialLimitAndFailureStopReasonsSettleTheTurn() {
        // Previously outside the allowlist, these left the turn open until the idle timeout.
        val completed = mapper.map(command, received(stopWith("max_steps")))
        assertEquals(RuntimeRunStatus.Completed, assertIs<RuntimeEventPayload.RunLifecycleChanged>(completed[1].payload).status)

        val failed = mapper.map(command, received(stopWith("llm_api_error")))
        val failure = assertIs<RuntimeEventPayload.RunLifecycleChanged>(failed[1].payload)
        assertEquals(RuntimeRunStatus.Failed, failure.status)
        assertEquals("App Server turn stopped with llm_api_error", failure.reason)
    }

    @Test
    fun inputAcceptedIsNotATurnEventAndTurnFinishedStaysObservable() {
        val ack = AppServerInboundFrame.InputAccepted("input-1", runtime, accepted = true, disposition = "started")
        assertEquals(emptyList(), mapper.map(command, received(ack)))

        val finished = AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = 9,
            emittedAt = "2026-09-15T00:00:00Z",
            idempotencyKey = "turn_finished:9",
            turnId = "turn-1",
            stopReason = "end_turn",
        )
        val drafts = mapper.map(command, received(finished))
        assertIs<RuntimeEventPayload.ExternalTransportFrame>(drafts[0].payload)
        assertEquals(RuntimeRunStatus.Completed, assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts[1].payload).status)
    }

    @Test
    fun turnFinishedCarriesTheAuthoritativeTerminal() {
        val cancelled = mapper.map(command, received(turnFinished("cancelled", runId = "run-7")))
        val cancel = assertIs<RuntimeEventPayload.RunLifecycleChanged>(cancelled[1].payload)
        assertEquals(RuntimeRunStatus.Cancelled, cancel.status)
        assertEquals("run-7", cancelled[1].runId?.value)

        val failed = mapper.map(command, received(turnFinished("llm_api_error", error = "provider down")))
        val failure = assertIs<RuntimeEventPayload.RunLifecycleChanged>(failed[1].payload)
        assertEquals(RuntimeRunStatus.Failed, failure.status)
        assertEquals("provider down", failure.reason)
    }

    @Test
    fun turnFinishedOnApprovalOrUnknownReasonStaysOpen() {
        listOf("requires_approval", "tool_use").forEach { reason ->
            val drafts = mapper.map(command, received(turnFinished(reason)))
            assertIs<RuntimeEventPayload.ExternalTransportFrame>(drafts.single().payload)
        }
    }

    @Test
    fun nonTerminalLoopErrorIsANoticeNotATurnEnd() {
        val drafts = mapper.map(
            command,
            received(
                streamDelta(
                    messageType = "loop_error",
                    body = buildJsonObject {
                        put("message_type", "loop_error")
                        put("message", "retrying provider")
                        put("is_terminal", false)
                    },
                ),
            ),
        )
        val notice = assertIs<RuntimeEventPayload.RemoteStreamFrame>(drafts.single().payload)
        assertEquals("loop_error", notice.messageType)
    }

    @Test
    fun loopErrorWithoutIsTerminalStillFailsTheTurn() {
        listOf(null, true).forEach { terminal ->
            val drafts = mapper.map(
                command,
                received(
                    streamDelta(
                        messageType = "loop_error",
                        body = buildJsonObject {
                            put("message_type", "loop_error")
                            put("message", "boom")
                            terminal?.let { put("is_terminal", it) }
                        },
                    ),
                ),
            )
            val failure = assertIs<RuntimeEventPayload.RunLifecycleChanged>(drafts.single().payload)
            assertEquals(RuntimeRunStatus.Failed, failure.status)
            assertEquals("boom", failure.reason)
        }
    }

    private fun turnFinished(reason: String, runId: String? = null, error: String? = null) =
        AppServerInboundFrame.TurnFinished(
            runtime = runtime,
            eventSeq = 9,
            emittedAt = "2026-09-15T00:00:00Z",
            idempotencyKey = "turn_finished:9",
            turnId = "turn-1",
            stopReason = reason,
            runId = runId,
            error = error,
        )

    private fun stopWith(reason: String) = streamDelta(
        messageType = "stop_reason",
        runId = "run-1",
        body = buildJsonObject {
            put("message_type", "stop_reason")
            put("run_id", "run-1")
            put("stop_reason", reason)
        },
    )

    private fun received(frame: AppServerInboundFrame): AppServerReceivedFrame =
        AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject {
                put("type", frame.type ?: "unknown")
                put("idempotency_key", "evt-1")
            },
        )

    private fun streamDelta(
        messageType: String,
        runId: String? = null,
        body: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("message_type", messageType)
            runId?.let { put("run_id", it) }
        },
    ): AppServerInboundFrame.StreamDelta =
        AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "2026-06-24T00:00:00Z",
            idempotencyKey = "evt-1",
            delta = body,
        )

    private companion object {
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
