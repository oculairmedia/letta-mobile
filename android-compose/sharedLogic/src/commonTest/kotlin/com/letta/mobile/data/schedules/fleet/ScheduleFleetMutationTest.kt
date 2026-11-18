package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.CronScheduleSource
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.CronRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mutations must retire older fleet reads and release a cancelled pause's pending mark. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleFleetMutationTest {
    @Test
    fun anOlderFleetReadCannotRestoreADeletedSchedule() = runTest {
        val held = CompletableDeferred<Unit>()
        var holdOld = true
        val source = CronScheduleSource { agent ->
            check(agent in listOf(AgentId("a1"), AgentId("a2")))
            if (agent == AgentId("a2") && holdOld) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { held.await() }
            }
            emptyList()
        }
        val native = FakeScheduleRepository()
        val controller = ScheduleFleetController(ScheduleFleetSources(FakeAgentRepository(), native, source), ScheduleFleetPolicy(), this)
        controller.refresh()
        runCurrent()
        holdOld = false
        controller.deleteNative(native.getSchedule("a1", "s1"))
        runCurrent()
        assertEquals(listOf("s2"), controller.state.value.schedules.map { it.id })
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("s2"), controller.state.value.schedules.map { it.id })
        controller.close()
    }

    @Test
    fun deletingACronCancelsAPauseAndDoesNotAskTheNativeRoute() = runTest {
        val tasks = mutableListOf(CronTask("c1", "a1", "conv", "Digest", "", "0 9 * * *", "UTC", true, "work", "active", "now"))
        val pause = CompletableDeferred<Unit>()
        var cancelled = false
        val transport = object : NoOpChannelTransport() {
            override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) =
                ServerFrame.CronListResponse(
                    id = "list", ts = "now", requestId = "list", success = true,
                    tasks = tasks.filter {
                        (agentId == null || it.agentId == agentId) &&
                            (conversationId == null || it.conversationId == conversationId)
                    },
                )

            override suspend fun sendCronPause(command: CronPauseCommand): ServerFrame.CronPauseResponse {
                val task = tasks.single { it.id == command.taskId }
                check(task.status in setOf("active", "paused"))
                try {
                    pause.await()
                    error("the paused call should be cancelled")
                } finally {
                    cancelled = true
                }
            }

            override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
                tasks.removeAll { it.id == taskId }
                return ServerFrame.CronDeleteResponse(id = "delete", ts = "now", requestId = "delete", success = true)
            }
        }
        val repository = CronRepository(transport, backgroundScope)
        val source = com.letta.mobile.data.schedules.RepositoryCronScheduleSource(repository)
        val native = FakeScheduleRepository().apply { error = RouteUnavailableException() }
        val controller = ScheduleFleetController(
            ScheduleFleetSources(FakeAgentRepository(), native, source),
            ScheduleFleetPolicy(nativeRoute = false), this,
        )
        controller.refresh()
        advanceUntilIdle()
        val task = controller.state.value.crons.single()
        controller.toggleCronPause(task, resume = false)
        runCurrent()
        assertEquals(setOf("c1"), controller.state.value.pendingCronIds)
        controller.deleteCron(task)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(emptySet(), controller.state.value.pendingCronIds)
        assertTrue(controller.state.value.crons.isEmpty())
        assertEquals(0, native.refreshScheduleCallCount)
        assertNull(controller.state.value.errorMessage)
        controller.deleteCron(task.copy(agentId = null))
        assertEquals("This schedule has no agent to delete it through; focus an agent to manage it.", controller.state.value.errorMessage)
        controller.close()
    }
}
