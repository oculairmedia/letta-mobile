package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.TestData
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRepositoryTest {

    private lateinit var fakeApi: FakeAgentApi
    private lateinit var repository: AgentRepository

    @Before
    fun setup() {
        fakeApi = FakeAgentApi()
        repository = AgentRepository(fakeApi, mockk(relaxed = true))
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
