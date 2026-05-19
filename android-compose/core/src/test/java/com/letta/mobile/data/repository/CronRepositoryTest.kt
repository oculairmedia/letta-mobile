package com.letta.mobile.data.repository

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-d52f.2 acceptance: dedup, push refresh, response-bound
 * mutations, reconnect resilience. Mocks [ChannelTransport] so the
 * tests stay transport-agnostic and don't require a live WS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class CronRepositoryTest {

    private lateinit var transport: ChannelTransport
    private lateinit var events: MutableSharedFlow<ServerFrame>
    private lateinit var state: MutableStateFlow<ChannelTransport.State>

    @Before
    fun setUp() {
        unmockkAll()
        transport = mockk(relaxed = false)
        events = MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
        state = MutableStateFlow(connectedState())
        every { transport.events } returns events
        every { transport.state } returns state
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `schedulesFlow triggers exactly one cron_list per agent regardless of subscribers`() = runTest {
        coEvery { transport.sendCronList(agentId = "agent-x") } returns
            successList("agent-x", listOf(task("t1", "agent-x")))
        val repo = CronRepository(transport, backgroundScope)

        // Three independent subscribers for the same agent.
        val s1 = repo.schedulesFlow("agent-x").first { it.isNotEmpty() }
        val s2 = repo.schedulesFlow("agent-x").first { it.isNotEmpty() }
        val s3 = repo.schedulesFlow("agent-x").first { it.isNotEmpty() }

        assertEquals(listOf("t1"), s1.map { it.id })
        assertEquals(listOf("t1"), s2.map { it.id })
        assertEquals(listOf("t1"), s3.map { it.id })

        // cron_list fired ONCE despite three subscriptions.
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-x") }
    }

    @Test
    fun `schedulesFlow returns current snapshot within one WS round-trip`() = runTest {
        coEvery { transport.sendCronList(agentId = "agent-y") } returns
            successList("agent-y", listOf(task("t1", "agent-y"), task("t2", "agent-y")))
        val repo = CronRepository(transport, backgroundScope)

        val tasks = withTimeout(5_000) {
            repo.schedulesFlow("agent-y").first { it.isNotEmpty() }
        }
        assertEquals(setOf("t1", "t2"), tasks.map { it.id }.toSet())
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-y") }
    }

    @Test
    fun `crons_updated push triggers a refresh of every initialized scope`() = runTest {
        coEvery { transport.sendCronList(agentId = "agent-a") } returnsMany listOf(
            successList("agent-a", listOf(task("a1", "agent-a"))),
            successList("agent-a", listOf(task("a1", "agent-a"), task("a2", "agent-a"))),
        )
        coEvery { transport.sendCronList(agentId = "agent-b") } returnsMany listOf(
            successList("agent-b", listOf(task("b1", "agent-b"))),
            successList("agent-b", listOf(task("b1", "agent-b"), task("b2", "agent-b"))),
        )
        val repo = CronRepository(transport, backgroundScope)

        // Initialize both scopes.
        repo.schedulesFlow("agent-a").first { it.isNotEmpty() }
        repo.schedulesFlow("agent-b").first { it.isNotEmpty() }
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-a") }
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-b") }

        // Push a crons_updated event.
        events.emit(
            ServerFrame.CronsUpdated(
                id = "u-1",
                ts = "2026-05-19T00:00:00Z",
                reason = "client_mutation",
                tasksActive = 4L,
                at = "2026-05-19T00:00:00Z",
            )
        )

        // Both scopes should refresh — wait until the second emission arrives.
        val agentA = withTimeout(2_000) { repo.schedulesFlow("agent-a").first { it.size == 2 } }
        val agentB = withTimeout(2_000) { repo.schedulesFlow("agent-b").first { it.size == 2 } }
        assertEquals(2, agentA.size)
        assertEquals(2, agentB.size)

        coVerify(exactly = 2) { transport.sendCronList(agentId = "agent-a") }
        coVerify(exactly = 2) { transport.sendCronList(agentId = "agent-b") }
    }

    @Test
    fun `addSchedule resolves on response and optimistically inserts into the cache`() = runTest {
        val initial = listOf(task("t1", "agent-x"))
        coEvery { transport.sendCronList(agentId = "agent-x") } returns
            successList("agent-x", initial)
        val newTask = task("t2", "agent-x", name = "new")
        coEvery {
            transport.sendCronAdd(
                agentId = "agent-x",
                name = "new",
                description = "d",
                prompt = "p",
                recurring = true,
                cron = "*/5 * * * *",
                every = null,
                at = null,
                timezone = null,
                conversationId = null,
            )
        } returns ServerFrame.CronAddResponse(
            id = "r-1",
            ts = "t",
            requestId = "req-1",
            success = true,
            task = newTask,
        )
        val repo = CronRepository(transport, backgroundScope)

        // Seed the cache.
        repo.schedulesFlow("agent-x").first { it.isNotEmpty() }

        val result = repo.addSchedule(
            CronAddParams(
                agentId = "agent-x",
                name = "new",
                description = "d",
                prompt = "p",
                recurring = true,
                cron = "*/5 * * * *",
            )
        )
        assertTrue("addSchedule returned $result", result.isSuccess)
        assertEquals("t2", result.getOrThrow().id)

        val afterAdd = repo.schedulesFlow("agent-x").first { it.size == 2 }
        assertEquals(setOf("t1", "t2"), afterAdd.map { it.id }.toSet())
    }

    @Test
    fun `addSchedule wraps shim error in Result failure`() = runTest {
        coEvery { transport.sendCronList(agentId = "agent-x") } returns
            successList("agent-x", emptyList())
        coEvery {
            transport.sendCronAdd(
                agentId = "agent-x",
                name = "bad",
                description = "d",
                prompt = "p",
                recurring = true,
                cron = "not-a-cron",
                every = null,
                at = null,
                timezone = null,
                conversationId = null,
            )
        } returns ServerFrame.CronAddResponse(
            id = "r",
            ts = "t",
            requestId = "req",
            success = false,
            error = "invalid cron expression",
        )
        val repo = CronRepository(transport, backgroundScope)
        repo.schedulesFlow("agent-x").first()

        val result = repo.addSchedule(
            CronAddParams(
                agentId = "agent-x",
                name = "bad",
                description = "d",
                prompt = "p",
                recurring = true,
                cron = "not-a-cron",
            )
        )
        assertTrue("addSchedule should fail", result.isFailure)
        assertEquals("invalid cron expression", result.exceptionOrNull()?.message)
        assertEquals(emptyList<CronTask>(), repo.schedulesFlow("agent-x").first())
    }

    @Test
    fun `deleteSchedule removes from cache on success`() = runTest {
        val initial = listOf(task("t1", "agent-x"), task("t2", "agent-x"))
        coEvery { transport.sendCronList(agentId = "agent-x") } returns
            successList("agent-x", initial)
        coEvery { transport.sendCronDelete("t1") } returns ServerFrame.CronDeleteResponse(
            id = "r", ts = "t", requestId = "req", success = true,
        )
        val repo = CronRepository(transport, backgroundScope)
        repo.schedulesFlow("agent-x").first { it.size == 2 }

        val result = repo.deleteSchedule("agent-x", "t1")
        assertTrue("delete returned $result", result.isSuccess)
        val after = repo.schedulesFlow("agent-x").first { it.size == 1 }
        assertEquals(listOf("t2"), after.map { it.id })
    }

    @Test
    fun `reconnect refreshes every initialized scope`() = runTest {
        coEvery { transport.sendCronList(agentId = "agent-x") } returnsMany listOf(
            successList("agent-x", listOf(task("t1", "agent-x"))),
            successList("agent-x", listOf(task("t1", "agent-x"), task("t2", "agent-x"))),
        )
        val repo = CronRepository(transport, backgroundScope)

        repo.schedulesFlow("agent-x").first { it.isNotEmpty() }
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-x") }

        // Simulate a WS drop + reconnect.
        state.value = ChannelTransport.State.Disconnected(1000, "idle timeout")
        // Yield once so the observer sees the Disconnected state.
        delay(10)
        state.value = connectedState()

        val after = withTimeout(2_000) { repo.schedulesFlow("agent-x").first { it.size == 2 } }
        assertEquals(2, after.size)
        coVerify(exactly = 2) { transport.sendCronList(agentId = "agent-x") }
    }

    @Test
    fun `refresh dedups parallel callers to a single in-flight WS round-trip`() = runTest {
        // Make sendCronList suspend briefly so two callers overlap.
        coEvery { transport.sendCronList(agentId = "agent-x") } coAnswers {
            delay(50)
            successList("agent-x", listOf(task("t1", "agent-x")))
        }
        val repo = CronRepository(transport, backgroundScope)

        // First subscriber triggers initial refresh; immediately ask for
        // a manual refresh while the first is still in flight.
        val initial = async(start = CoroutineStart.UNDISPATCHED) {
            repo.schedulesFlow("agent-x").first { it.isNotEmpty() }
        }
        val manual = async(start = CoroutineStart.UNDISPATCHED) { repo.refresh("agent-x") }

        initial.await()
        val manualResult = manual.await()
        assertTrue(manualResult.isSuccess)

        // Both callers shared the same in-flight call.
        coVerify(exactly = 1) { transport.sendCronList(agentId = "agent-x") }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun connectedState() = ChannelTransport.State.Connected(
        serverId = "srv",
        sessionId = "sess",
        deviceId = "dev",
    )

    private fun task(id: String, agentId: String, name: String = id): CronTask = CronTask(
        id = id,
        agentId = agentId,
        conversationId = "conv-$agentId",
        name = name,
        description = "test",
        cron = "*/5 * * * *",
        timezone = "UTC",
        recurring = true,
        prompt = "do thing",
        status = "active",
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun successList(@Suppress("UNUSED_PARAMETER") agentId: String, tasks: List<CronTask>) =
        ServerFrame.CronListResponse(
            id = "r-${tasks.size}",
            ts = "t",
            requestId = "req",
            success = true,
            tasks = tasks,
        )
}

