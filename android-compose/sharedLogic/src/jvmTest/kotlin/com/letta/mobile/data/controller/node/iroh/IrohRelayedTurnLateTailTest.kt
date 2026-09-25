package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.18: `turn_finished` is the last frame the phone gets for a relayed turn,
 * even when the engine's flow yields assistant deltas after its terminal lifecycle draft (the
 * settle-window job racing the collect loop). The late tail is delivered, ahead of the terminal.
 */
class IrohRelayedTurnLateTailTest {

    @Test
    fun turnFinishedIsTheLastFrameWhenDeltasArriveAfterTheTerminal() = runTest {
        val kinds = relayedKinds(listOf(assistant("Hel"), completed(), assistant("Hello"), assistant("Hello!")))

        assertEquals("turn_finished", kinds.last(), "turn_finished must close the stream: $kinds")
        val finished = kinds.indexOf("turn_finished")
        val lastAssistant = kinds.lastIndexOf("assistant_message")
        assertTrue(lastAssistant in 0 until finished, "late deltas are relayed before the terminal: $kinds")
        assertEquals(3, kinds.count { it == "assistant_message" }, "no late delta is dropped: $kinds")
    }

    @Test
    fun onlyOneTerminalIsRelayedWhenTheEngineRepeatsIt() = runTest {
        val kinds = relayedKinds(listOf(assistant("Hi"), completed(), assistant("Hi!"), completed()))

        assertEquals(1, kinds.count { it == "turn_finished" }, "one turn_finished: $kinds")
        assertEquals("turn_finished", kinds.last())
    }

    @Test
    fun inOrderTurnStillEndsWithStopReasonIdleAndTurnFinished() = runTest {
        val kinds = relayedKinds(listOf(assistant("Hi"), completed()))

        assertEquals(listOf("stop_reason", "update_loop_status", "turn_finished"), kinds.takeLast(3))
    }

    /** Relays [drafts] (as the engine yielded them) to a fake phone; returns its stream frame kinds. */
    private suspend fun TestScope.relayedKinds(drafts: List<RuntimeEventDraft>): List<String?> {
        val phone = FakePhoneLink(runtime, CLIENT_MESSAGE_ID, requestId = "req-1", writeScope = backgroundScope)
        phone.relay(controllerRunning { flow { drafts.forEach { emit(it) } } }, turnCommandFor(runtime, CLIENT_MESSAGE_ID))
        testScheduler.advanceUntilIdle()
        return phone.stream.map { it.kind }
    }

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-late-tail"
        val runtime = AppServerRuntimeScope("agent-late", "conv-late")
        var seq = 0L

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-late:conv-late"),
            agentId = AgentId(runtime.agentId),
            conversationId = ConversationId(runtime.conversationId),
            runId = RunId("run-late"),
            source = RuntimeEventSource.RemoteLetta,
            payload = payload,
        )

        fun assistant(content: String): RuntimeEventDraft {
            seq += 1
            val body = buildJsonObject {
                put("type", "stream_delta")
                put("event_seq", seq)
                put("idempotency_key", "late-$seq")
                put(
                    "delta",
                    buildJsonObject {
                        put("message_type", "assistant_message")
                        put("otid", "otid-late")
                        put("id", "letta-msg-late")
                        put("content", content)
                    },
                )
            }.toString()
            return draft(RuntimeEventPayload.RemoteStreamFrame(frameId = "f-$seq", body = body))
        }

        fun completed() = draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed, reason = "end_turn"))
    }
}
