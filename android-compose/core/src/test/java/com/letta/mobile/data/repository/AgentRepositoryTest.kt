package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentRepositoryTest {

    private lateinit var fakeApi: FakeAgentApi
    private lateinit var repository: AgentRepository

    @Before
    fun setup() {
        fakeApi = FakeAgentApi()
        repository = AgentRepository(fakeApi, mockk(relaxed = true))
    }

    @Test
    fun `concurrent refreshAgentsIfStale callers share one list request`() = runTest {
        fakeApi.agents.add(TestData.agent(id = "a1", name = "Agent One"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshAgentsIfStale(maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listAgents" })
        assertEquals(listOf("a1"), repository.agents.value.map { it.id })
    }

    @Test
    fun `refreshAgents hydrates cache with paged offset requests`() = runTest {
        repeat(125) { index ->
            fakeApi.agents.add(TestData.agent(id = "a$index", name = "Agent $index"))
        }

        repository.refreshAgents()

        assertEquals(125, repository.agents.value.size)
        assertEquals(List(7) { 20 }, fakeApi.listLimits)
        assertEquals(listOf(0, 20, 40, 60, 80, 100, 120), fakeApi.listOffsets)
    }

    @Test
    fun `deleteAgent removes deleted agent from cached agents immediately`() = runTest {
        fakeApi.agents.addAll(
            listOf(
                TestData.agent(id = "a1", name = "Agent One"),
                TestData.agent(id = "a2", name = "Agent Two"),
            )
        )
        repository.refreshAgents()

        repository.deleteAgent("a1")

        assertEquals(listOf("a2"), repository.agents.value.map { it.id })
        assertFalse(fakeApi.agents.any { it.id == "a1" })
    }
}
