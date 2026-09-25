package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.28: a relayed turn ends with the App Server's own frames, not a bare
 * re-synthesized error_message, and the conversation's queue updates keep coming after it.
 */
class IrohRelayedServerTailTest {

    @Test
    fun failedTurnRelaysTheServersErrorStopReasonIdleAndTurnFinishedInOrder() = runTest {
        val server = listOf(
            delta("assistant_message") { put("content", "Hi") },
            delta("error_message") { put("message", "400 bad model") },
            delta("stop_reason") { put("stop_reason", "error") },
            idle(),
            turnFinished("error"),
        )
        val drafts = listOf(remote(server[0]), lifecycle(RuntimeRunStatus.Failed, "400 bad model"))

        val stream = relay(server, drafts)

        assertEquals(
            listOf("assistant_message", "error_message", "stop_reason", "update_loop_status", "turn_finished"),
            stream.map { it.kind },
        )
        val error = stream[1].json["delta"] as JsonObject
        assertEquals(RUN, error.parityString("run_id"), "the phone keeps the run id: $error")
        assertEquals("400 bad model", error.parityString("message"))
        assertEquals("error", stream.last().json.parityString("stop_reason"))
        assertEquals(RUN, stream.last().json.parityString("run_id"))
    }

    @Test
    fun cancelWithoutAServerTerminalDeltaStaysACancel() = runTest {
        val server = listOf(delta("assistant_message") { put("content", "Hi") }, idle(), turnFinished("cancelled"))
        val drafts = listOf(remote(server[0]), lifecycle(RuntimeRunStatus.Cancelled, "App Server loop idle after abort"))

        val stream = relay(server, drafts)

        assertEquals(listOf("assistant_message", "stop_reason", "update_loop_status", "turn_finished"), stream.map { it.kind })
        val stop = stream[1].json["delta"] as JsonObject
        assertEquals("cancelled", stop.parityString("stop_reason"))
        assertEquals(RUN, stop.parityString("run_id"))
        assertEquals("App Server loop idle after abort", stop.parityString("message"))
    }

    @Test
    fun cancelWithoutATapIsRelayedAsACancelledStopReason() = runTest {
        val stream = relay(server = null, drafts = listOf(lifecycle(RuntimeRunStatus.Cancelled, "stopped")))

        val stop = stream.first { it.kind == "stop_reason" }.json["delta"] as JsonObject
        assertEquals("cancelled", stop.parityString("stop_reason"))
        assertEquals(RUN, stop.parityString("run_id"))
        assertTrue(stream.none { it.kind == "error_message" }, "a cancel is not a failure: ${stream.map { it.kind }}")
    }

    @Test
    fun completionAfterTheRealStopReasonGetsNoSecondStopReason() = runTest {
        val server = listOf(
            delta("assistant_message") { put("content", "Hi") },
            delta("stop_reason") { put("stop_reason", "end_turn") },
            idle(),
            turnFinished("end_turn"),
        )
        val drafts = listOf(remote(server[0]), remote(server[1]), lifecycle(RuntimeRunStatus.Completed, null))

        val stream = relay(server, drafts)

        assertEquals(
            listOf("assistant_message", "stop_reason", "update_loop_status", "turn_finished"),
            stream.map { it.kind },
        )
    }

    @Test
    fun queueUpdatesAfterTurnFinishedStillReachTheInitiator() = runTest {
        val server = listOf(
            delta("assistant_message") { put("content", "Hi") },
            idle(),
            turnFinished("cancelled"),
            queue(items = 1),
            queue(items = 0),
            queue(items = 0),
        )
        val drafts = listOf(remote(server[0]), lifecycle(RuntimeRunStatus.Cancelled, null))

        val stream = relay(server, drafts)

        val kinds = stream.map { it.kind }
        assertEquals(listOf("turn_finished", "update_queue", "update_queue"), kinds.takeLast(3), "$kinds")
        assertEquals(true, (stream[stream.size - 2].json["queue"].toString()).contains("\"paused\":true"))
    }

    /** Relays [drafts] to a fake phone while the tap reads [server]; returns the phone's stream frames. */
    private suspend fun TestScope.relay(server: List<JsonObject>?, drafts: List<RuntimeEventDraft>): List<WireFrame> {
        val phone = FakePhoneLink(runtime, CLIENT_MESSAGE_ID, requestId = "req-1", writeScope = backgroundScope)
        val frames = server?.let { list -> flow { list.forEach { emit(AppServerProtocol.decodeFrame(it.toString(), AppServerChannel.Stream)) } } }
        phone.relay(controllerRunning(frames) { flow { drafts.forEach { emit(it) } } }, turnCommandFor(runtime, CLIENT_MESSAGE_ID))
        testScheduler.advanceUntilIdle()
        return phone.stream.filterNot { it.kind == "user_message" }
            .dropWhile { it.type == "update_loop_status" && it.loopStatus != "WAITING_ON_INPUT" }
    }

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-tail"
        const val RUN = "run-tail"
        val runtime = AppServerRuntimeScope("agent-tail", "conv-tail")
        private val runtimeJson = AppServerProtocol.json.encodeToJsonElement(AppServerRuntimeScope.serializer(), runtime)

        private var seq = 0L

        fun frame(type: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            seq += 1
            put("type", type)
            put("runtime", runtimeJson)
            put("idempotency_key", "tail-$seq")
            put("event_seq", seq)
            put("emitted_at", "2026-09-25T00:00:00Z")
            fields()
        }

        fun delta(messageType: String, fields: JsonObjectBuilder.() -> Unit) = frame("stream_delta") {
            put(
                "delta",
                buildJsonObject {
                    put("message_type", messageType)
                    put("run_id", RUN)
                    fields()
                },
            )
        }

        fun idle() = frame("update_loop_status") {
            put(
                "loop_status",
                buildJsonObject {
                    put("status", "WAITING_ON_INPUT")
                    put("active_run_ids", buildJsonArray { })
                },
            )
        }

        fun turnFinished(stopReason: String) = frame("turn_finished") {
            put("turn_id", "turn-tail")
            put("stop_reason", stopReason)
            put("run_id", RUN)
        }

        /** A queue snapshot; a non-empty one is parked (paused) after an abort. */
        fun queue(items: Int) = frame("update_queue") {
            put(
                "queue",
                buildJsonArray {
                    repeat(items) {
                        add(
                            buildJsonObject {
                                put("id", "q-$it")
                                put("client_message_id", "cm-other-$it")
                                put("kind", "message")
                                put("source", "user")
                                put("content", "x")
                                put("enqueued_at", "2026-09-25T00:00:00Z")
                                put("paused", true)
                            },
                        )
                    }
                },
            )
            put("removed", buildJsonArray { })
        }

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-tail:conv-tail"),
            agentId = AgentId(runtime.agentId),
            conversationId = ConversationId(runtime.conversationId),
            runId = RunId(RUN),
            source = RuntimeEventSource.LocalRuntime,
            payload = payload,
        )

        fun remote(frame: JsonObject) = draft(
            RuntimeEventPayload.RemoteStreamFrame(frameId = frame.parityString("idempotency_key")!!, body = frame.toString()),
        )

        fun lifecycle(status: RuntimeRunStatus, reason: String?) =
            draft(RuntimeEventPayload.RunLifecycleChanged(status = status, reason = reason))
    }
}
