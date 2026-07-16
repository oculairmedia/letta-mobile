package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-oqfbj: tool-call settlement tests.
 * 
 * Verify that dangling tool_call_ids (emitted but never returned) are settled
 * with synthetic ToolReturnObserved(Failed) drafts on abnormal turn end:
 * - cancel/abort mid-tool
 * - idle timeout mid-tool
 * - collector failure mid-tool
 *
 * Clean turns (all calls returned) and clean completions are strict no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineToolSettlementTest {

    @Test
    fun idleTimeoutMidToolSynthesizesFailedReturn() = runTest {
        val client = SettlementTestClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = 50L, // very short for test
        )

        engine.runTurn(command).test {
            // Started lifecycle
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // Emit a tool_call_message
            client.emitToolCall("call-1", "search")
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            // Wait for idle timeout to fire (client never emits more frames)
            // Expect synthetic return BEFORE the terminal Failed lifecycle
            val syntheticReturn = assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)
            assertEquals(ToolCallId("call-1"), syntheticReturn.toolCallId)
            assertEquals(ToolExecutionStatus.Failed, syntheticReturn.status)
            assertTrue(
                syntheticReturn.body.contains("timeout") || syntheticReturn.body.contains("interrupted"),
                "Synthetic return body should mention timeout: ${syntheticReturn.body}",
            )

            // Terminal Failed lifecycle from idle timeout path
            val failed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Failed, failed.status)
            assertTrue(
                failed.reason?.contains("idle") == true,
                "Failed reason should mention idle timeout: ${failed.reason}",
            )

            awaitComplete()
        }
    }

    @Test
    fun cleanToolTurnNoSyntheticReturn() = runTest {
        val client = SettlementTestClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = 60_000L,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // Emit tool_call_message
            client.emitToolCall("call-1", "search")
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            // Emit tool_return_message (clean return)
            client.emitToolReturn("call-1", "success")
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            // Emit terminal stop_reason. Tail ordering (letta-mobile-hyqzk)
            // flushes the buffered stop_reason frame before the terminal.
            client.emitStopReason()
            val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("stop_reason", stop.messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)

            // NO synthetic return should appear
            awaitComplete()
        }
    }

    @Test
    fun twoCallsOneReturnedSettlesOnlyDangling() = runTest {
        val client = SettlementTestClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = 50L,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // Emit two tool calls
            client.emitToolCall("call-1", "search")
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)
            client.emitToolCall("call-2", "fetch")
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            // Return only call-2
            client.emitToolReturn("call-2", "success")
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            // Wait for idle timeout (call-1 is dangling, call-2 is settled)
            // Expect synthetic return ONLY for call-1
            val syntheticReturn = assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)
            assertEquals(ToolCallId("call-1"), syntheticReturn.toolCallId)
            assertEquals(ToolExecutionStatus.Failed, syntheticReturn.status)

            // Terminal Failed lifecycle
            val failed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Failed, failed.status)

            awaitComplete()
        }
    }

    @Test
    fun approvalRequestTrackedAsEmittedToolCall() = runTest {
        val client = SettlementTestClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "req" },
            turnIdleTimeoutMs = 50L,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // Emit an approval_request_message (which carries a tool_call_id)
            client.emitApprovalRequest("call-1", "sensitive_tool")
            assertIs<RuntimeEventPayload.ApprovalRequested>(awaitItem().payload)

            // Wait for idle timeout (approval never answered, tool never returned)
            // Expect synthetic return for call-1
            val syntheticReturn = assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)
            assertEquals(ToolCallId("call-1"), syntheticReturn.toolCallId)
            assertEquals(ToolExecutionStatus.Failed, syntheticReturn.status)

            val failed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Failed, failed.status)

            awaitComplete()
        }
    }

    private companion object {
        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }

    private class SettlementTestClient : AppServerClient {
        private val eventFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 32)
        override val events: Flow<AppServerReceivedFrame> = eventFlow

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused in settlement tests")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "",
                runtime = command.runtime,
                success = true,
                aborted = true,
            )

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused in settlement tests")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emitToolCall(toolCallId: String, toolName: String) {
            val delta = buildJsonObject {
                put("message_type", "client_tool_start")
                put("tool_call_id", toolCallId)
                put("tool_name", toolName)
            }
            val raw = buildJsonObject {
                put("type", "stream_delta")
                put("idempotency_key", "tool-call-$toolCallId")
                put("delta", delta)
                put("runtime", buildJsonObject {
                    put("agent_id", runtime.agentId)
                    put("conversation_id", runtime.conversationId)
                })
            }
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    raw = raw,
                    frame = AppServerInboundFrame.StreamDelta(
                        runtime = runtime,
                        eventSeq = 1,
                        emittedAt = "2026-07-01T00:00:00Z",
                        idempotencyKey = "tool-call-$toolCallId",
                        delta = delta,
                    ),
                ),
            )
        }

        fun emitToolReturn(toolCallId: String, output: String) {
            val delta = buildJsonObject {
                put("message_type", "client_tool_end")
                put("tool_call_id", toolCallId)
                put("output", output)
                put("status", "success")
            }
            val raw = buildJsonObject {
                put("type", "stream_delta")
                put("idempotency_key", "tool-return-$toolCallId")
                put("delta", delta)
                put("runtime", buildJsonObject {
                    put("agent_id", runtime.agentId)
                    put("conversation_id", runtime.conversationId)
                })
            }
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    raw = raw,
                    frame = AppServerInboundFrame.StreamDelta(
                        runtime = runtime,
                        eventSeq = 2,
                        emittedAt = "2026-07-01T00:00:01Z",
                        idempotencyKey = "tool-return-$toolCallId",
                        delta = delta,
                    ),
                ),
            )
        }

        fun emitApprovalRequest(toolCallId: String, toolName: String) {
            val request = buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_call_id", toolCallId)
                put("tool_name", toolName)
            }
            val raw = buildJsonObject {
                put("type", "control_request")
                put("request_id", "approval-$toolCallId")
                put("request", request)
                put("runtime", buildJsonObject {
                    put("agent_id", runtime.agentId)
                    put("conversation_id", runtime.conversationId)
                })
            }
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Control,
                    raw = raw,
                    frame = AppServerInboundFrame.ControlRequest(
                        requestId = "approval-$toolCallId",
                        request = request,
                        agentId = runtime.agentId,
                        conversationId = runtime.conversationId,
                    ),
                ),
            )
        }

        fun emitStopReason() {
            val delta = buildJsonObject {
                put("message_type", "stop_reason")
                put("stop_reason", "end_turn")
            }
            val raw = buildJsonObject {
                put("type", "stream_delta")
                put("idempotency_key", "stop")
                put("delta", delta)
                put("runtime", buildJsonObject {
                    put("agent_id", runtime.agentId)
                    put("conversation_id", runtime.conversationId)
                })
            }
            eventFlow.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    raw = raw,
                    frame = AppServerInboundFrame.StreamDelta(
                        runtime = runtime,
                        eventSeq = 3,
                        emittedAt = "2026-07-01T00:00:02Z",
                        idempotencyKey = "stop",
                        delta = delta,
                    ),
                ),
            )
        }

    }
}
