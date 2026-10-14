package com.letta.mobile.desktop

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.ui.shell.pages.schedules.CronTaskId
import com.letta.mobile.ui.shell.pages.schedules.NativeScheduleRef
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-by4vm.42: desktop deletes go to the list the page names, never by id lookup. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopDestinationWiringScheduleDeleteTest {
    private val cronDeletes = mutableListOf<String>()
    private val scheduleDeletes = mutableListOf<Pair<String, String>>()

    @Test
    fun aCronIdEqualToAScheduleIdDeletesOnlyTheCron() = runTest {
        val actions = wiredActions()

        actions.onDeleteCron(CronTaskId("s1"))
        advanceUntilIdle()

        assertEquals(listOf("s1"), cronDeletes)
        assertEquals(emptyList(), scheduleDeletes)
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
        assertEquals(emptyList(), cronDeletes)
    }

    private fun TestScope.wiredActions(): SchedulesPageActions {
        val controller = ScheduleLibraryController(agents, schedules, scope = this)
        controller.start()
        advanceUntilIdle()
        return destinationScheduleActions(
            ScheduleWiringDeps(
                schedules = controller,
                cronPanel = DesktopCronPanelState(crons, this),
                scheduleLibraryState = controller.state.value,
                selectedAgentId = "a1",
            ),
        )
    }

    /** Only what the wiring touches; any other call fails the test. */
    private val crons = fake<CronApi> { method, args ->
        when (method.name) {
            "listCrons" -> listOf(CronTask(id = "s1", agentId = "a1"))
            "deleteCron" -> { cronDeletes += args[0] as String }
            else -> error("unused: ${method.name}")
        }
    }

    private val schedules = fake<IScheduleRepository> { method, args ->
        when (method.name) {
            "refreshSchedules" -> Unit
            "getSchedules" -> flowOf(if (args[0] == "a1") listOf(NATIVE_S1) else emptyList())
            "deleteSchedule" -> { scheduleDeletes += args[0] as String to args[1] as String }
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
