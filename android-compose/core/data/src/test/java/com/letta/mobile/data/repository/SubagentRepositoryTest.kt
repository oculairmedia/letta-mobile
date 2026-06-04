package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.testutil.FakeChannelTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-73o2h.3 acceptance for the active-subagent registry repo:
 * dedup, push folds-by-replacement, todos round-trip, reconnect resilience.
 * Mirrors [CronRepositoryTest]; uses [FakeChannelTransport] so tests stay
 * transport-agnostic without mocking the stateful concrete WS class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class SubagentRepositoryTest {

    private lateinit var transport: FakeChannelTransport

    @Before
    fun setUp() {
        transport = FakeChannelTransport(initialState = connectedState())
    }

    @Test
    fun `activeSubagentsFlow triggers exactly one subagent_list regardless of subscribers`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val s1 = repo.activeSubagentsFlow().first { it.isNotEmpty() }
        val s2 = repo.activeSubagentsFlow().first { it.isNotEmpty() }
        val s3 = repo.activeSubagentsFlow().first { it.isNotEmpty() }

        assertEquals(listOf("toolu_1"), s1.map { it.toolCallId })
        assertEquals(listOf("toolu_1"), s2.map { it.toolCallId })
        assertEquals(listOf("toolu_1"), s3.map { it.toolCallId })

        // subagent_list fired ONCE despite three subscriptions.
        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `activeSubagentsFlow returns current snapshot within one WS round-trip`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"), running("toolu_2"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val subagents = withTimeout(5_000) {
            repo.activeSubagentsFlow().first { it.isNotEmpty() }
        }
        assertEquals(setOf("toolu_1", "toolu_2"), subagents.map { it.toolCallId }.toSet())
        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `subagents_updated push folds fresh active snapshot in by replacement`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        repo.activeSubagentsFlow().first { it.isNotEmpty() }
        assertEquals(1, transport.subagentListCalls.size)

        // Push a subagents_updated with a fresh canonical active set.
        transport.events.emit(
            ServerFrame.SubagentsUpdated(
                id = "u-1",
                ts = "2026-06-01T00:00:00Z",
                reason = "started",
                subagent = running("toolu_2"),
                subagentsActive = listOf(running("toolu_1"), running("toolu_2")),
                at = "2026-06-01T00:00:00Z",
            )
        )

        val after = withTimeout(2_000) { repo.activeSubagentsFlow().first { it.size == 2 } }
        assertEquals(setOf("toolu_1", "toolu_2"), after.map { it.toolCallId }.toSet())
        // Folded by replacement — NO additional subagent_list round-trip.
        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `subagents_updated with empty active set clears the snapshot`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)
        repo.activeSubagentsFlow().first { it.isNotEmpty() }

        transport.events.emit(
            ServerFrame.SubagentsUpdated(
                id = "u-2",
                ts = "t",
                reason = "completed",
                subagent = running("toolu_1").copy(status = SubagentStatus.COMPLETED),
                subagentsActive = emptyList(),
                at = "t",
            )
        )

        val after = withTimeout(2_000) { repo.activeSubagentsFlow().first { it.isEmpty() } }
        assertEquals(emptyList<SubagentEntry>(), after)
    }

    @Test
    fun `todos resolves on response and returns the snapshot`() = runTest {
        transport.enqueueSubagentTodos(
            "toolu_1",
            ServerFrame.SubagentTodosResponse(
                id = "r",
                ts = "t",
                requestId = "r2",
                success = true,
                found = true,
                todosFound = true,
                todos = listOf(
                    SubagentTodo(content = "one", status = "completed", activeForm = "Doing one"),
                    SubagentTodo(content = "two", status = "in_progress", activeForm = "Doing two"),
                ),
            ),
        )
        val repo = SubagentRepository(transport, backgroundScope)

        val result = repo.todos("toolu_1")
        assertTrue("todos returned $result", result.isSuccess)
        assertEquals(listOf("one", "two"), result.getOrThrow().map { it.content })
        assertEquals(listOf("toolu_1"), transport.subagentTodosCalls)
    }

    @Test
    fun `todos wraps shim error in Result failure`() = runTest {
        transport.enqueueSubagentTodos(
            "toolu_x",
            ServerFrame.SubagentTodosResponse(
                id = "r",
                ts = "t",
                requestId = "r2",
                success = false,
                error = "unknown subagent",
            ),
        )
        val repo = SubagentRepository(transport, backgroundScope)

        val result = repo.todos("toolu_x")
        assertTrue("todos should fail", result.isFailure)
        assertEquals("unknown subagent", result.exceptionOrNull()?.message)
    }

    @Test
    fun `reconnect refreshes the active snapshot`() = runTest {
        transport.enqueueSubagentList(
            successList(listOf(running("toolu_1"))),
            successList(listOf(running("toolu_1"), running("toolu_2"))),
        )
        val repo = SubagentRepository(transport, backgroundScope)

        repo.activeSubagentsFlow().first { it.isNotEmpty() }
        assertEquals(1, transport.subagentListCalls.size)

        // Simulate a WS drop + reconnect.
        transport.state.value = ChannelTransport.State.Disconnected(1000, "idle timeout")
        delay(10)
        transport.state.value = connectedState()

        val after = withTimeout(2_000) { repo.activeSubagentsFlow().first { it.size == 2 } }
        assertEquals(2, after.size)
        assertEquals(2, transport.subagentListCalls.size)
    }

    @Test
    fun `refresh dedups parallel callers to a single in-flight WS round-trip`() = runTest {
        transport.subagentListDelayMs = 50
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val initial = async(start = CoroutineStart.UNDISPATCHED) {
            repo.activeSubagentsFlow().first { it.isNotEmpty() }
        }
        val manual = async(start = CoroutineStart.UNDISPATCHED) { repo.refresh() }

        initial.await()
        val manualResult = manual.await()
        assertTrue(manualResult.isSuccess)

        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `refresh wraps an unsuccessful subagent_list in Result failure`() = runTest {
        transport.enqueueSubagentList(
            ServerFrame.SubagentListResponse(
                id = "r",
                ts = "t",
                requestId = "r1",
                success = false,
                error = "registry unavailable",
            )
        )
        val repo = SubagentRepository(transport, backgroundScope)

        val result = repo.refresh()
        assertTrue("refresh should fail", result.isFailure)
        assertEquals("registry unavailable", result.exceptionOrNull()?.message)
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun connectedState() = ChannelTransport.State.Connected(
        serverId = "srv",
        sessionId = "sess",
        deviceId = "dev",
    )

    private fun running(toolCallId: String): SubagentEntry = SubagentEntry(
        toolCallId = toolCallId,
        description = "test subagent",
        subagentType = "general-purpose",
        status = SubagentStatus.RUNNING,
        taskId = null,
        subagentAgentId = null,
        parentRunId = "run-1",
        startedAt = "2026-06-01T00:00:00Z",
    )

    private fun successList(subagents: List<SubagentEntry>) = ServerFrame.SubagentListResponse(
        id = "r-${subagents.size}",
        ts = "t",
        requestId = "r1",
        success = true,
        subagents = subagents,
    )
}
