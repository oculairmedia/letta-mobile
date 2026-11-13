package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-6vf4r (S3): a device that opens a conversation while another device's turn runs
 * gets that turn's `update_loop_status` replayed by the node. It must learn from it that a turn
 * runs (and see that turn end on the idle loop status), not drop it because it owns no turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IrohObserverMidTurnJoinTest : IrohObserverFixture() {
    @Test
    fun aReplayedProcessingLoopStatusTellsTheJoiningDeviceATurnRuns() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)

        ingestor.ingestObserverFrame(
            ObserverFrameRequest(loopStatusFrame(LoopFrameSpec(AGENT, CONVERSATION, 1L, PROCESSING, listOf(RUN))), 1L),
        )

        val running = assertIs<ServerFrame.RunActivity>(emitted.singleOrNull(), "the joining device learns the turn runs: $emitted")
        assertEquals(CONVERSATION, running.conversationId)
        assertEquals(RUN, running.runId)
        assertEquals(PROCESSING, assertIs<RuntimeEventPayload.LoopPhaseChanged>(running.payload).status)
    }

    @Test
    fun theTurnLearnedFromTheReplayEndsOnTheIdleLoopStatus() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = observerIngestor(IrohTurnRegistry(), emitted)

        ingestor.ingestObserverFrame(
            ObserverFrameRequest(loopStatusFrame(LoopFrameSpec(AGENT, CONVERSATION, 1L, PROCESSING, listOf(RUN))), 1L),
        )
        ingestor.ingestObserverFrame(
            ObserverFrameRequest(loopStatusFrame(LoopFrameSpec(AGENT, CONVERSATION, 2L, "WAITING_ON_INPUT")), 1L),
        )

        val done = assertIs<ServerFrame.TurnDone>(emitted.last(), "the idle loop status ends the observed turn")
        assertEquals(RUN, done.runId)
        assertEquals("completed", done.status)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "local-conv-95806427"
        const val RUN = "local-run-205"
        const val PROCESSING = "PROCESSING_API_RESPONSE"
    }
}
