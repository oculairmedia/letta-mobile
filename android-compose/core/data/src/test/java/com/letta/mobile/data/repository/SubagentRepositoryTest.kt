package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ChannelTransportState
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

    private val parentScope = SubagentParentScope("agent-parent", "default")

    private lateinit var transport: FakeChannelTransport

    @Before
    fun setUp() {
        transport = FakeChannelTransport(initialState = connectedState())
    }

    @Test
    fun `activeSubagentsFlow triggers exactly one subagent_list regardless of subscribers`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val s1 = repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
        val s2 = repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
        val s3 = repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }

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
            repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
        }
        assertEquals(setOf("toolu_1", "toolu_2"), subagents.map { it.toolCallId }.toSet())
        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `subagents_updated push folds fresh active snapshot in by replacement`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
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

        val after = withTimeout(2_000) { repo.activeSubagentsFlow(parentScope).first { it.size == 2 } }
        assertEquals(setOf("toolu_1", "toolu_2"), after.map { it.toolCallId }.toSet())
        // Folded by replacement — NO additional subagent_list round-trip.
        assertEquals(1, transport.subagentListCalls.size)
    }

    @Test
    fun `subagents_updated with explicit terminal replaces running entry with durable terminal`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)
        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }

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

        val after = withTimeout(2_000) {
            repo.activeSubagentsFlow(parentScope).first { it.singleOrNull()?.status == SubagentStatus.COMPLETED }
        }
        assertEquals(listOf(SubagentStatus.COMPLETED), after.map { it.status })
        assertTrue(after.single().terminalAtEpochMs != null)
    }

    @Test
    fun `subagents_updated partial snapshot retains omitted running entries`() = runTest {
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)
        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }

        transport.events.emit(
            ServerFrame.SubagentsUpdated(
                id = "u-partial",
                ts = "t",
                reason = "registry-gap",
                subagent = running("toolu_2"),
                subagentsActive = listOf(running("toolu_2")),
                at = "t",
            )
        )

        val after = withTimeout(2_000) { repo.activeSubagentsFlow(parentScope).first { it.size == 2 } }
        assertEquals(setOf("toolu_1", "toolu_2"), after.map { it.toolCallId }.toSet())
    }

    @Test
    fun `refresh partial snapshot retains existing running entries`() = runTest {
        transport.enqueueSubagentList(
            successList(listOf(running("toolu_1"))),
            successList(listOf(running("toolu_2"))),
        )
        val repo = SubagentRepository(transport, backgroundScope)
        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }

        repo.refresh().getOrThrow()

        val after = repo.activeSubagentsFlow(parentScope).first { it.size == 2 }
        assertEquals(setOf("toolu_1", "toolu_2"), after.map { it.toolCallId }.toSet())
    }

    @Test
    fun `two agents sharing default conversation receive isolated snapshots`() = runTest {
        val agentOne = running("toolu-agent-1")
        val agentTwo = running("toolu-agent-2").copy(parentAgentId = "agent-other")
        transport.enqueueSubagentList(successList(listOf(agentOne, agentTwo)))
        val repo = SubagentRepository(transport, backgroundScope)

        val first = repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
        val second = repo.activeSubagentsFlow(SubagentParentScope("agent-other", "default"))
            .first { it.isNotEmpty() }

        assertEquals(listOf("toolu-agent-1"), first.map { it.toolCallId })
        assertEquals(listOf("toolu-agent-2"), second.map { it.toolCallId })
    }

    @Test
    fun `conversation switch projects only the selected parent conversation`() = runTest {
        val firstConversation = running("toolu-conv-a").copy(parentConversationId = "conv-a")
        val secondConversation = running("toolu-conv-b").copy(parentConversationId = "conv-b")
        transport.enqueueSubagentList(successList(listOf(firstConversation, secondConversation)))
        val repo = SubagentRepository(transport, backgroundScope)

        val first = repo.activeSubagentsFlow(SubagentParentScope("agent-parent", "conv-a"))
            .first { it.isNotEmpty() }
        val second = repo.activeSubagentsFlow(SubagentParentScope("agent-parent", "conv-b"))
            .first { it.isNotEmpty() }

        assertEquals(listOf("toolu-conv-a"), first.map { it.toolCallId })
        assertEquals(listOf("toolu-conv-b"), second.map { it.toolCallId })
    }

    @Test
    fun `terminal lifecycle remains cached after resubscribe beyond sharing timeout`() = runTest {
        var now = 1_000L
        transport.enqueueSubagentList(successList(listOf(running("toolu-terminal"))))
        val repo = SubagentRepository(transport, backgroundScope, clock = { now })
        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }

        transport.events.emit(
            ServerFrame.SubagentsUpdated(
                id = "terminal",
                ts = "t",
                reason = "completed",
                subagent = running("toolu-terminal").copy(status = SubagentStatus.COMPLETED),
                subagentsActive = emptyList(),
                at = "t",
            )
        )
        val terminal = repo.activeSubagentsFlow(parentScope).first { it.singleOrNull()?.status == SubagentStatus.COMPLETED }
        assertEquals(1_000L, terminal.single().terminalAtEpochMs)

        now += 6_000L
        val resubscribed = repo.currentActiveSubagents(parentScope)
        assertEquals(listOf("toolu-terminal"), resubscribed.map { it.toolCallId })
        assertEquals(1_000L, resubscribed.single().terminalAtEpochMs)
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

        repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
        assertEquals(1, transport.subagentListCalls.size)

        // Simulate a WS drop + reconnect.
        transport.state.value = ChannelTransportState.Disconnected(1000, "idle timeout")
        delay(10)
        transport.state.value = connectedState()

        val after = withTimeout(2_000) { repo.activeSubagentsFlow(parentScope).first { it.size == 2 } }
        assertEquals(2, after.size)
        assertEquals(2, transport.subagentListCalls.size)
    }

    @Test
    fun `refresh dedups parallel callers to a single in-flight WS round-trip`() = runTest {
        transport.subagentListDelayMs = 50
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val initial = async(start = CoroutineStart.UNDISPATCHED) {
            repo.activeSubagentsFlow(parentScope).first { it.isNotEmpty() }
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


    @Test
    fun `refresh concurrent callers share one deferred and do not cancel each other`() = runTest {
        transport.subagentListDelayMs = 50
        transport.enqueueSubagentList(successList(listOf(running("toolu_1"))))
        val repo = SubagentRepository(transport, backgroundScope)

        val r1 = async { repo.refresh() }
        val r2 = async { repo.refresh() }

        val res1 = r1.await()
        val res2 = r2.await()

        assertTrue("First caller should succeed", res1.isSuccess)
        assertTrue("Second caller should succeed", res2.isSuccess)
        assertEquals(1, transport.subagentListCalls.size)
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun connectedState() = ChannelTransportState.Connected(
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
        parentAgentId = "agent-parent",
        parentConversationId = "default",
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
