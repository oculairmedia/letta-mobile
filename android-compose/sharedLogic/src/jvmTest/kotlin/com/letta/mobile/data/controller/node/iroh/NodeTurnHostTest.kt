package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.3: an initiator's Iroh connection closing must not cancel the server turn.
 * The turn runs on the node-owned [NodeTurnHost]; the connection only awaits it. When the
 * connection is cancelled the turn is detached: it keeps collecting to its terminal, keeps
 * reaching the remaining viewers, stops writing to the dead initiator, and parks its tail for
 * the initiator's redial.
 */
class NodeTurnHostTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    private class RecordingViewer(override val connectionId: String) : ViewerHandle {
        val frames = mutableListOf<String>()
        override suspend fun writeFrame(frame: String): Boolean {
            frames += frame
            return true
        }
    }

    private class ScriptedTurn(val host: NodeTurnHost, val turn: NodeTurn, val drafts: Channel<RuntimeEventPayload>) {
        var collectorCancelled = false
    }

    private fun TestScope.scriptedTurn(
        viewers: () -> Set<ViewerHandle>,
        initiator: ViewerHandle,
        parked: ParkedTerminalStore,
    ): ScriptedTurn {
        val host = NodeTurnHost(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val tracker = TurnFrameTracker()
        val fanout = ConversationTurnFanout(
            conversationId = runtime.conversationId,
            runtime = runtime,
            viewersFor = { viewers() },
            initiatorViewer = initiator,
            trackInitiatorFrame = { tracker.track(it) },
        )
        val drafts = Channel<RuntimeEventPayload>(Channel.UNLIMITED)
        lateinit var scripted: ScriptedTurn
        val turn = NodeTurn(
            conversationId = runtime.conversationId,
            clientMessageId = CLIENT_MESSAGE_ID,
            fanout = fanout,
            tracker = tracker,
            parkedTerminals = parked,
        ) {
            // Stands in for controller.runTurn(command).collect { fanout.onDraft(...) }.
            try {
                for (payload in drafts) fanout.onDraft(payload)
            } catch (cancelled: CancellationException) {
                scripted.collectorCancelled = true
                throw cancelled
            }
        }
        scripted = ScriptedTurn(host, turn, drafts)
        return scripted
    }

    @Test
    fun closingTheInitiatorDetachesTheTurnAndParksItsTerminal() = runTest {
        val initiator = RecordingViewer("peer-A")
        val observer = RecordingViewer("peer-B")
        val parked = ParkedTerminalStore()
        val scripted = scriptedTurn({ setOf(initiator, observer) }, initiator, parked)
        val connection = launch { scripted.host.run(scripted.turn) }
        scripted.drafts.send(assistantFrame("hello"))
        runCurrent()
        assertEquals(1, initiator.frames.size)

        connection.cancel(CancellationException("iroh connection closed"))
        runCurrent()

        assertTrue(connection.isCancelled)
        assertEquals(1, scripted.host.liveTurnCount, "the turn outlives its initiator's connection")
        scripted.drafts.send(assistantFrame("hello world"))
        scripted.drafts.send(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed))
        scripted.drafts.close()
        runCurrent()

        assertFalse(scripted.collectorCancelled, "the runTurn collector must keep running to its terminal")
        assertEquals(0, scripted.host.liveTurnCount)
        assertEquals(1, initiator.frames.size, "nothing more is written to the dead initiator")
        assertTrue(observer.frames.any { "stop_reason" in it }, "remaining viewers still get the terminal")
        val parkedFrames = assertNotNull(parked.takeParked(CLIENT_MESSAGE_ID), "the tail is parked for redial")
        assertTrue("stop_reason" in parkedFrames, "the real terminal is parked")
        assertFalse("connection interrupted" in parkedFrames, "no synthetic terminal when the real one exists")
    }

    @Test
    fun redialedHandleFromTheSamePeerReceivesTheRestOfADetachedTurn() = runTest {
        val initiator = RecordingViewer("peer-A")
        val redialed = RecordingViewer("peer-A")
        var viewers: Set<ViewerHandle> = setOf(initiator)
        val scripted = scriptedTurn({ viewers }, initiator, ParkedTerminalStore())
        val connection = launch { scripted.host.run(scripted.turn) }
        scripted.drafts.send(assistantFrame("hello"))
        runCurrent()

        connection.cancel()
        runCurrent()
        viewers = setOf(redialed)
        scripted.drafts.send(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed))
        scripted.drafts.close()
        runCurrent()

        assertEquals(1, initiator.frames.size)
        assertTrue(redialed.frames.any { "stop_reason" in it }, "the redialed peer is an ordinary observer")
    }

    @Test
    fun turnThatFinishesWhileAttachedIsNotParked() = runTest {
        val initiator = RecordingViewer("peer-A")
        val parked = ParkedTerminalStore()
        val scripted = scriptedTurn({ setOf(initiator) }, initiator, parked)
        val connection = launch { scripted.host.run(scripted.turn) }
        scripted.drafts.send(RuntimeEventPayload.RunLifecycleChanged(status = RuntimeRunStatus.Completed))
        scripted.drafts.close()
        runCurrent()

        assertTrue(connection.isCompleted && !connection.isCancelled)
        assertTrue(initiator.frames.any { "stop_reason" in it })
        assertNull(parked.takeParked(CLIENT_MESSAGE_ID))
    }

    private fun assistantFrame(text: String) = RuntimeEventPayload.RemoteStreamFrame(
        frameId = "frame-$text",
        messageId = null,
        messageType = null,
        body = buildJsonObject {
            put("type", "stream_delta")
            put("runtime", buildJsonObject {
                put("agent_id", runtime.agentId)
                put("conversation_id", runtime.conversationId)
            })
            put("event_seq", 1)
            put("delta", buildJsonObject {
                put("message_type", "assistant_message")
                put("id", "msg-1")
                put("otid", "otid-1")
                put("content", text)
            })
        }.toString(),
    )

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-1"
    }
}
