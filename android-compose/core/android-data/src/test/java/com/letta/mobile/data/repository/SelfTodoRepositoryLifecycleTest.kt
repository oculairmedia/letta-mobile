package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.testutil.FakeChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelfTodoRepositoryLifecycleTest {
    @Test
    fun `records every terminal turn status`() = runTest(UnconfinedTestDispatcher()) {
        val transport = connectedTransport()
        val repository = repository(transport)

        listOf(
            "completed" to SubagentStatus.COMPLETED,
            "failed" to SubagentStatus.FAILED,
            "cancelled" to SubagentStatus.CANCELLED,
        ).forEachIndexed { index, (wireStatus, expected) ->
            val runId = "run-$index"
            val turnId = "turn-$index"
            transport.events.emit(started(runId, turnId))
            transport.events.emit(done(runId, turnId, wireStatus))
            advanceUntilIdle()
            assertEquals(expected, repository.snapshotFor(CONVERSATION_ID).lifecycleStatus)
        }
    }

    @Test
    fun `stale terminal frame cannot suppress newer run`() = runTest(UnconfinedTestDispatcher()) {
        val transport = connectedTransport()
        val repository = repository(transport)

        transport.events.emit(started("old-run", "old-turn"))
        transport.events.emit(started("new-run", "new-turn"))
        transport.events.emit(done("old-run", "old-turn", "completed"))
        advanceUntilIdle()
        assertEquals(SubagentStatus.RUNNING, repository.snapshotFor(CONVERSATION_ID).lifecycleStatus)

        transport.events.emit(done("new-run", "new-turn", "completed"))
        advanceUntilIdle()
        assertEquals(SubagentStatus.COMPLETED, repository.snapshotFor(CONVERSATION_ID).lifecycleStatus)
    }

    private fun TestScope.repository(transport: FakeChannelTransport): SelfTodoRepository =
        SelfTodoRepository(
            transport = transport,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

    private fun connectedTransport() = FakeChannelTransport(
        initialState = ChannelTransportState.Connected("server", "session", "device"),
    )

    private fun started(runId: String, turnId: String) = ServerFrame.TurnStarted(
        id = "started-$runId",
        ts = "2026-08-16T00:00:00Z",
        agentId = "agent-1",
        conversationId = CONVERSATION_ID,
        turnId = turnId,
        runId = runId,
    )

    private fun done(runId: String, turnId: String, status: String) = ServerFrame.TurnDone(
        id = "done-$runId",
        ts = "2026-08-16T00:00:01Z",
        turnId = turnId,
        runId = runId,
        status = status,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
    }
}
