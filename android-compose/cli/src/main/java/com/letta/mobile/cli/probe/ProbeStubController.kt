package com.letta.mobile.cli.probe

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.TurnCommand
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * Behavior knobs for the hermetic stub app-server, including the documented
 * red-path regression injections used by the probe gate's acceptance self-check:
 *
 *  - [suppressTerminal] (env `LETTA_PROBE_STUB_SUPPRESS_TERMINAL=1`): the stub
 *    never emits the terminal `stop_reason` frame — simulates the historical
 *    lost-terminal regressions (q71yi/c0qm0 family). Probe must exit nonzero
 *    with `timeout_missing_terminal`.
 *  - [untypedFrames] (env `LETTA_PROBE_STUB_UNTYPED_FRAMES=1`): assistant deltas
 *    are emitted WITHOUT `message_type` — simulates the plain-body
 *    AssistantMessage fallback. Probe must exit nonzero with `untyped_frames_*`.
 */
data class ProbeStubBehavior(
    val assistantDeltas: Int = 3,
    val deltaDelayMs: Long = 200,
    val emitToolCall: Boolean = true,
    val suppressTerminal: Boolean = false,
    val untypedFrames: Boolean = false,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): ProbeStubBehavior = ProbeStubBehavior(
            suppressTerminal = env("LETTA_PROBE_STUB_SUPPRESS_TERMINAL") == "1",
            untypedFrames = env("LETTA_PROBE_STUB_UNTYPED_FRAMES") == "1",
        )
    }
}

/**
 * Deterministic in-process app-server controller for hermetic iroh probe runs.
 *
 * Emits fully-typed `stream_delta` envelopes (reasoning, tool_call/tool_return,
 * assistant deltas, terminal stop_reason) with a real `run_id` and strictly
 * monotonic `event_seq`, honors `abort_message` midstream (synthesizing a
 * tool_return for any open tool_call before the cancelled terminal), and
 * records run status + messages into [store] so the local HTTP admin API can
 * answer `message.list` paging and run-status polls.
 */
class ProbeStubController(
    private val store: ProbeStubStore,
    private val behavior: ProbeStubBehavior = ProbeStubBehavior(),
) : AppServerController {
    override val state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)

    private val eventSeq = AtomicLong(0)
    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()

    private data class ActiveRun(
        val runId: String,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
    )

    override suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime = CanonicalRuntime(
        scope = AppServerRuntimeScope(
            agentId = agentId.value,
            conversationId = conversationId.value,
            actingUserId = null,
        ),
        agent = null,
        conversation = null,
        created = null,
    )

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
        val runtime = AppServerRuntimeScope(
            agentId = command.agentId.value,
            conversationId = command.conversationId.value,
        )
        val runId = "run-${UUID.randomUUID()}"
        val turnId = "turn-${UUID.randomUUID()}"
        val run = ActiveRun(runId)
        val runtimeKey = runtimeKey(runtime)
        activeRuns[runtimeKey] = run
        store.runStatuses[runId] = "running"

        try {
            emitDelta(command, runtime, delta = buildJsonObject {
                put("message_type", "reasoning_message")
                put("id", "stub-reasoning-$turnId")
                put("run_id", runId)
                put("turn_id", turnId)
                put("reasoning", "stub reasoning for '${textOf(command)}'")
            })

            var openToolCallId: String? = null
            if (behavior.emitToolCall) {
                val toolCallId = "stub-toolcall-$turnId"
                openToolCallId = toolCallId
                emitDelta(command, runtime, delta = buildJsonObject {
                    put("message_type", "tool_call_message")
                    put("id", "stub-tc-msg-$turnId")
                    put("run_id", runId)
                    put("turn_id", turnId)
                    put("tool_call", buildJsonObject {
                        put("name", "stub_tool")
                        put("tool_call_id", toolCallId)
                        put("arguments", "{}")
                    })
                })
            }

            val assistantMessageId = "stub-assistant-$turnId"
            val assistantText = StringBuilder()
            var cancelled = false
            for (index in 0 until behavior.assistantDeltas) {
                delay(behavior.deltaDelayMs.milliseconds)
                if (run.cancelRequested.get()) {
                    cancelled = true
                    break
                }
                if (index == 0 && openToolCallId != null) {
                    emitDelta(command, runtime, delta = buildJsonObject {
                        put("message_type", "tool_return_message")
                        put("id", "stub-tr-msg-$turnId")
                        put("run_id", runId)
                        put("turn_id", turnId)
                        put("tool_call_id", openToolCallId)
                        put("tool_return", "stub tool output")
                        put("status", "success")
                    })
                    openToolCallId = null
                }
                val piece = "stub reply part ${index + 1} for '${textOf(command)}'. "
                assistantText.append(piece)
                emitDelta(command, runtime, delta = buildJsonObject {
                    if (!behavior.untypedFrames) put("message_type", "assistant_message")
                    put("id", assistantMessageId)
                    put("run_id", runId)
                    put("turn_id", turnId)
                    put("content", assistantText.toString())
                })
            }
            cancelled = cancelled || run.cancelRequested.get()

            if (cancelled && openToolCallId != null) {
                // Never leave a dangling tool_call at cancel (8s45p contract).
                emitDelta(command, runtime, delta = buildJsonObject {
                    put("message_type", "tool_return_message")
                    put("id", "stub-tr-cancel-$turnId")
                    put("run_id", runId)
                    put("turn_id", turnId)
                    put("tool_call_id", openToolCallId)
                    put("tool_return", "cancelled")
                    put("status", "error")
                })
            }

            if (behavior.suppressTerminal) {
                store.runStatuses[runId] = "completed"
                return@flow
            }

            if (cancelled) {
                store.runStatuses[runId] = "cancelled"
                emitDelta(command, runtime, delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "cancelled")
                    put("status", "cancelled")
                    put("run_id", runId)
                    put("turn_id", turnId)
                })
            } else {
                store.append(runtime.conversationId, "user_message", textOf(command))
                store.append(runtime.conversationId, "assistant_message", assistantText.toString())
                store.runStatuses[runId] = "completed"
                emitDelta(command, runtime, delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "end_turn")
                    put("status", "completed")
                    put("run_id", runId)
                    put("turn_id", turnId)
                })
            }
        } finally {
            activeRuns.remove(runtimeKey, run)
            if (store.runStatuses[runId] == "running") store.runStatuses[runId] = "cancelled"
        }
    }

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse = AppServerInboundFrame.SyncResponse(
        requestId = "stub-sync-${UUID.randomUUID()}",
        runtime = runtime,
        success = true,
    )

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        val run = activeRuns[runtimeKey(runtime)]
        val aborted = if (run != null && (runId == null || runId == run.runId)) {
            run.cancelRequested.set(true)
            true
        } else {
            false
        }
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = "stub-abort-${UUID.randomUUID()}",
            runtime = runtime,
            aborted = aborted,
            success = true,
            error = if (aborted) null else "no active run" + (runId?.let { " matching $it" } ?: ""),
        )
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RuntimeEventDraft>.emitDelta(
        command: TurnCommand,
        runtime: AppServerRuntimeScope,
        delta: JsonObject,
    ) {
        val envelope = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", buildJsonObject {
                put("agent_id", runtime.agentId)
                put("conversation_id", runtime.conversationId)
            })
            put("event_seq", eventSeq.incrementAndGet())
            put("emitted_at", Instant.now().toString())
            put("idempotency_key", "stub-delta-${UUID.randomUUID()}")
            put("delta", delta)
        }.toString()
        emit(
            RuntimeEventDraft(
                backendId = command.backendId,
                runtimeId = command.runtimeId,
                agentId = command.agentId,
                conversationId = command.conversationId,
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.RemoteStreamFrame(
                    frameId = "stub-frame-${UUID.randomUUID()}",
                    messageId = null,
                    messageType = null,
                    body = envelope,
                ),
            ),
        )
    }

    private fun runtimeKey(runtime: AppServerRuntimeScope): String =
        "${runtime.agentId}:${runtime.conversationId}"

    private fun textOf(command: TurnCommand): String =
        (command.input as? com.letta.mobile.runtime.TurnInput.UserMessage)?.text ?: ""
}
