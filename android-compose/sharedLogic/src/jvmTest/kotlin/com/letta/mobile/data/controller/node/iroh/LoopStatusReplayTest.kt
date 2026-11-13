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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * letta-mobile-6vf4r (S3): only a relayed turn's initiator got its `update_loop_status`, so a
 * device that opened the conversation mid-turn did not know a turn was running (and its send hit
 * the busy rejection). A viewer that joins while the run is bound now gets the same frame; one that
 * joins after the turn finished gets none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopStatusReplayTest {
    private class RecordingViewer(override val connectionId: String) : ViewerHandle {
        val frames = CopyOnWriteArrayList<String>()
        override suspend fun writeFrame(frame: String): Boolean = frames.add(frame)
    }

    @Test
    fun aViewerJoiningMidTurnLearnsTheRunningTurnAndOneJoiningAfterItDoesNot() = runTest {
        val host = NodeTurnHost.childOf(backgroundScope)
        val connections = ConnectionRegistry()
        connections.addViewerJoinedListener { conversationId, viewer -> host.replayLoopStatus(conversationId, viewer) }
        val phone = FakePhoneLink(runtime, CLIENT_MESSAGE_ID, requestId = "req-1", writeScope = backgroundScope)
        phone.protocol.onHost(host)
        val release = CompletableDeferred<Unit>()
        val controller = controllerRunning {
            flow {
                emit(draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Running)))
                release.await()
                emit(draft(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed, reason = "end_turn")))
            }
        }
        val relay = launch { phone.relay(controller, turnCommandFor(runtime, CLIENT_MESSAGE_ID)) }
        runCurrent()

        val midTurn = RecordingViewer("laptop")
        connections.register(runtime.conversationId, connections.claim(midTurn))

        val replayed = midTurn.frames.singleOrNull() ?: fail("a viewer joining mid-turn gets the loop status: ${midTurn.frames}")
        val frame = Json.parseToJsonElement(replayed).jsonObject
        assertEquals("update_loop_status", frame["type"]?.jsonPrimitive?.content)
        val loopStatus = frame.getValue("loop_status").jsonObject
        assertEquals(listOf(RUN), loopStatus.getValue("active_run_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf(CLIENT_MESSAGE_ID),
            (loopStatus.getValue("client_message_ids_by_run_id").jsonObject.getValue(RUN) as JsonArray).map { it.jsonPrimitive.content },
        )

        release.complete(Unit)
        relay.join()
        runCurrent()
        val afterTurn = RecordingViewer("tablet")
        connections.register(runtime.conversationId, connections.claim(afterTurn))
        assertTrue(afterTurn.frames.isEmpty(), "a finished turn is not replayed: ${afterTurn.frames}")
    }

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-follow-up"
        const val RUN = "local-run-205"
        val runtime = AppServerRuntimeScope("agent-1", "local-conv-95806427")

        fun draft(payload: RuntimeEventPayload) = RuntimeEventDraft(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:${runtime.agentId}:${runtime.conversationId}"),
            agentId = AgentId(runtime.agentId),
            conversationId = ConversationId(runtime.conversationId),
            runId = RunId(RUN),
            source = RuntimeEventSource.RemoteLetta,
            payload = payload,
        )
    }
}
