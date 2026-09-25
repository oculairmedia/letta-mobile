package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.9: cancelling a lease whose input is still QUEUED on the App Server takes
 * that input off the queue (`remove_queue_item`) and never aborts the turn ahead of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineQueuedCancelTest {

    @Test
    fun cancellingAQueuedLeaseRemovesItsQueueItemWithoutAborting() = runTest {
        val turn = startQueuedTurn()
        turn.client.emit(frames.updateQueue(QueueUpdateFixture.of(QueueItemFixture("item-7", LOCAL_MESSAGE_ID))))
        runCurrent()
        assertTrue(turn.engine.isQueued(AGENT, CONV), "the input waits in the server queue")

        turn.job.cancel()
        advanceUntilIdle()

        assertEquals(listOf("item-7"), turn.client.removals.map { it.itemId })
        assertTrue(turn.client.aborts.isEmpty(), "a queued lease has no run; an abort would hit the turn ahead")
        assertFalse(turn.engine.isBusy(AGENT, CONV))
    }

    @Test
    fun handoverOfAQueuedLeaseKeepsItsQueueItem() = runTest {
        val turn = startQueuedTurn()
        turn.client.emit(frames.updateQueue(QueueUpdateFixture.of(QueueItemFixture("item-7", LOCAL_MESSAGE_ID))))
        runCurrent()

        turn.job.cancel(TurnHandoverCancellation())
        advanceUntilIdle()

        assertTrue(turn.client.removals.isEmpty(), "a handed-over turn is still wanted")
        assertTrue(turn.client.aborts.isEmpty())
    }

    @Test
    fun queuedLeaseWithoutAKnownQueueItemReleasesCleanly() = runTest {
        val turn = startQueuedTurn()

        turn.job.cancel()
        advanceUntilIdle()

        assertTrue(turn.client.removals.isEmpty(), "no snapshot names the item, so there is nothing to remove")
        assertTrue(turn.client.aborts.isEmpty())
        assertFalse(turn.engine.isBusy(AGENT, CONV))
    }

    private class QueueRecordingClient : TurnEngineTestStreamClient() {
        val removals = mutableListOf<AppServerCommand.RemoveQueueItem>()
        val aborts = mutableListOf<AppServerCommand.AbortMessage>()

        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted =
            frames.inputAccepted(InputAckFixture.Queued)

        override suspend fun removeQueueItem(
            command: AppServerCommand.RemoveQueueItem,
        ): AppServerInboundFrame.RemoveQueueItemResponse {
            removals += command
            return AppServerInboundFrame.RemoveQueueItemResponse(requestId = command.requestId, success = true, itemId = command.itemId)
        }

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            aborts += command
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }
    }

    private class QueuedTurn(val client: QueueRecordingClient, val engine: AppServerTurnEngine, val job: Job)

    private fun TestScope.startQueuedTurn(): QueuedTurn {
        val client = QueueRecordingClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { TEST_INPUT_REQUEST_ID },
            turnIdleTimeoutMs = 60_000,
            terminalSettleQuietMs = 10,
            nowMs = { testScheduler.currentTime },
        )
        val job = launch { engine.runTurn(command).collect { } }
        runCurrent()
        return QueuedTurn(client, engine, job)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val LOCAL_MESSAGE_ID = "local-1"
        val frames = TurnEngineTestFrames(AppServerRuntimeScope(AGENT, CONV))
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:$AGENT:$CONV"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONV),
            input = TurnInput.UserMessage(localMessageId = LOCAL_MESSAGE_ID, text = "hey"),
        )
    }
}
