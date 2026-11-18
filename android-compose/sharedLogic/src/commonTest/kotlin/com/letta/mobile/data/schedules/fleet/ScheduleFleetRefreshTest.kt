package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.schedules.RepositoryCronScheduleSource
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeAgentRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.FakeScheduleRepository
import com.letta.mobile.data.schedules.ScheduleLibraryControllerTest.RouteUnavailableException
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.CronPauseCommand
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** A refresh that fails everywhere keeps the rows on screen and whatever the user changed meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleFleetRefreshTest {
    @Test
    fun aRefreshFailingEverywhereKeepsTheRowsAndTheNewerFilterAndPendingMark() = runTest {
        val transport = HeldCronTransport()
        val repository = CronRepository(transport, backgroundScope)
        val native = FakeScheduleRepository().apply { error = RouteUnavailableException() }
        val controller = ScheduleFleetController(
            ScheduleFleetSources(FakeAgentRepository(), native, RepositoryCronScheduleSource(repository)),
            ScheduleFleetPolicy(nativeRoute = false), this,
        )
        controller.refresh()
        advanceUntilIdle()
        assertEquals(listOf("c-a1", "c-a2"), controller.state.value.crons.map { it.id })

        transport.failReads = CompletableDeferred()
        controller.refresh()
        runCurrent()
        controller.filterAgent(AgentId("a2"))
        controller.toggleCronPause(controller.state.value.crons.first(), resume = false)
        runCurrent()
        transport.failReads?.complete(Unit)
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf("c-a1", "c-a2"), state.crons.map { it.id }, "the rows on screen stay")
        assertEquals("a2", state.agentFilter)
        assertEquals(setOf("c-a1"), state.pendingCronIds)
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        controller.close()
    }

    /** Lists one cron per agent; once [failReads] is set, a list waits for it and then fails. Pauses never answer. */
    private class HeldCronTransport : NoOpChannelTransport() {
        var failReads: CompletableDeferred<Unit>? = null

        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long): ServerFrame.CronListResponse {
            failReads?.let { gate ->
                gate.await()
                error("crons down")
            }
            val tasks = listOfNotNull(agentId).map {
                CronTask("c-$it", it, "conv", "Digest", "", "0 9 * * *", "UTC", true, "work", "active", "now")
            }
            return ServerFrame.CronListResponse(id = "list", ts = "now", requestId = "list", success = true, tasks = tasks)
        }

        override suspend fun sendCronPause(command: CronPauseCommand): ServerFrame.CronPauseResponse =
            CompletableDeferred<ServerFrame.CronPauseResponse>().await()
    }
}
