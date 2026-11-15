package com.letta.mobile.ui.shell.pages

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.CronPauseCommand
import com.letta.mobile.data.transport.api.CronResumeCommand
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.ui.shell.pages.schedules.NativeScheduleRef
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** letta-mobile-by4vm.42 / ckmub.17.5: a schedule delete goes to its own agent's list, never by id lookup. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellScheduleActionsTest {
    private val scheduleDeletes = mutableListOf<Pair<String, String>>()
    private val scheduleCreates = mutableListOf<String>()

    private val gate = CompletableDeferred<Unit>()
    private var hostStatus = "active"
    private var fail = false
    private var taskPresent = true
    private val calls = mutableListOf<String>()

    private fun cronTransport() = object : NoOpChannelTransport() {
        override suspend fun sendCronList(agentId: String?, conversationId: String?, timeoutMs: Long) =
            ServerFrame.CronListResponse(
                id = "list", ts = "now", requestId = "list", success = true,
                tasks = listOf(CronTask(
                    "cron-1", "a1", "conv", "Digest", "", "0 9 * * *", "UTC", true, "work", hostStatus, "now",
                )).filter { taskPresent },
            )
        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
            calls += "delete:$taskId"
            taskPresent = false
            return ServerFrame.CronDeleteResponse(id = "delete", ts = "now", requestId = "delete", success = true)
        }
        override suspend fun sendCronPause(command: CronPauseCommand):
            ServerFrame.CronPauseResponse {
            calls += "pause:${command.taskId}"
            gate.await()
            // The host may confirm a status different from the requested one.
            hostStatus = "paused"
            return ServerFrame.CronPauseResponse(id = "pause", ts = "now", requestId = "pause", success = true)
        }
        override suspend fun sendCronResume(command: CronResumeCommand):
            ServerFrame.CronResumeResponse {
            calls += "resume:${command.taskId}"
            if (!fail) hostStatus = "completed"
            return ServerFrame.CronResumeResponse(
                id = "resume", ts = "now", requestId = "resume", success = !fail,
                error = if (fail) "Host refused resume" else null,
            )
        }
    }

    @Test
    fun pauseAndResumeCallTheRepositoryOnceAndPublishOnlyTheRefreshedHostState() = runTest {
        val transport = cronTransport()
        val repository = CronRepository(transport, backgroundScope)
        val graph = fake<SessionRepositoryGraph> { method, _ ->
            when (method.name) {
                "getAgentRepository" -> agents
                "getScheduleRepository" -> schedules
                "getCronRepository" -> repository
                "getChannelTransport" -> transport
                "getCapabilities" -> SessionCapabilities(
                    setOf(SessionRoute.SCHEDULES),
                )
                else -> error("unused: ${method.name}")
            }
        }
        val controller = ShellLibraryFactory.schedules(graph, this)
        controller.selectAgent("a1")
        val workspace = ShellWorkspaceState().apply { selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a1"))) }
        controller.start()
        advanceUntilIdle()
        val actions = shellScheduleActions(controller, workspace)
        val toggle = assertNotNull(actions.onTogglePause)
        toggle("cron-1", false)
        toggle("cron-1", false)
        runCurrent()
        assertEquals(listOf("pause:cron-1"), calls)
        assertEquals("active", controller.state.value.crons.single().status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("paused", controller.state.value.crons.single().status)
        assertEquals(emptySet(), controller.state.value.pendingCronIds)
        fail = true
        toggle("cron-1", true)
        advanceUntilIdle()
        assertEquals("paused", controller.state.value.crons.single().status)
        assertEquals("Host refused resume", controller.state.value.errorMessage)
        fail = false
        toggle("cron-1", true)
        advanceUntilIdle()
        assertEquals("completed", controller.state.value.crons.single().status)
        actions.onDeleteCron(com.letta.mobile.ui.shell.pages.schedules.CronTaskId("cron-1"))
        advanceUntilIdle()
        assertEquals(emptyList(), controller.state.value.crons)
        workspace.selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a2")))
        toggle("cron-1", false)
        advanceUntilIdle()
        assertEquals(listOf("pause:cron-1", "resume:cron-1", "resume:cron-1", "delete:cron-1"), calls)
        controller.close()
    }

    @Test
    fun aScheduleDeleteGoesToItsAgentAndOtherAgentsAreDropped() = runTest {
        val actions = wiredActions()

        actions.onDeleteSchedule(NativeScheduleRef(agentId = "a2", scheduleId = "s1"))
        advanceUntilIdle()
        assertEquals(emptyList(), scheduleDeletes)

        actions.onDeleteSchedule(NativeScheduleRef(agentId = "a1", scheduleId = "s1"))
        advanceUntilIdle()
        assertEquals(listOf("a1" to "s1"), scheduleDeletes)
    }

    @Test
    fun aStaleScheduleRowCannotDeleteAfterWorkspaceFocusChanges() = runTest {
        val workspace = ShellWorkspaceState().apply { selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a1"))) }
        val actions = wiredActions(workspace)
        workspace.selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a2")))
        actions.onDeleteSchedule(NativeScheduleRef(agentId = "a1", scheduleId = "s1"))
        advanceUntilIdle()
        assertEquals(emptyList(), scheduleDeletes)
    }

    @Test
    fun aStaleScheduleFormCannotCreateAfterWorkspaceFocusChanges() = runTest {
        val workspace = ShellWorkspaceState().apply { selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a1"))) }
        val actions = wiredActions(workspace)
        workspace.selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a2")))
        actions.onCreate(ScheduleCreateRequest("a1", "Digest", "work", "0 18 * * *", "UTC"))
        advanceUntilIdle()
        assertEquals(emptyList(), scheduleCreates)
    }

    private fun TestScope.wiredActions(
        workspace: ShellWorkspaceState = ShellWorkspaceState().apply {
            selectFocus(CurrentFocus(pageRoute = "schedules", agentId = AgentId("a1")))
        },
    ): SchedulesPageActions {
        val controller = ScheduleLibraryController(agents, schedules, scope = this, initialAgentId = "a1")
        controller.start()
        advanceUntilIdle()
        return shellScheduleActions(controller, workspace)
    }

    /** Only what the wiring touches; any other call fails the test. */
    private val schedules = fake<IScheduleRepository> { method, args ->
        when (method.name) {
            "refreshSchedules" -> Unit
            "getSchedules" -> flowOf(if (args[0] == "a1") listOf(NATIVE_S1) else emptyList())
            "deleteSchedule" -> { scheduleDeletes += args[0] as String to args[1] as String }
            "createSchedule" -> {
                val agent = args[0] as String
                check(agent == "a1" || agent == "a2")
                scheduleCreates += agent
                NATIVE_S1.copy(id = "created", agentId = agent)
            }
            else -> error("unused: ${method.name}")
        }
    }

    private val agents = fake<IAgentRepository> { method, _ ->
        when (method.name) {
            "listAgentSummaries" -> listOf(AgentSummary(AgentId("a1"), "One"), AgentSummary(AgentId("a2"), "Two"))
            else -> error("unused: ${method.name}")
        }
    }

    private companion object {
        val NATIVE_S1 = ScheduledMessage(
            id = "s1",
            agentId = "a1",
            message = SchedulePayload(messages = listOf(ScheduleMessage(content = "hello", role = "user"))),
            schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 9 * * *"),
        )

        /** A JDK proxy for [T]: suspend members get their continuation last and may answer directly. */
        inline fun <reified T : Any> fake(noinline answer: (Method, Array<Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "fake ${T::class.simpleName}"
                    else -> answer(method, args ?: emptyArray())
                }
            } as T
    }
}
