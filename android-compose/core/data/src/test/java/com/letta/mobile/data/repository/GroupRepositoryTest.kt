package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.testutil.FakeGroupApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class GroupRepositoryTest {
    private lateinit var fakeApi: FakeGroupApi
    private lateinit var repository: GroupRepository

    @Before
    fun setup() {
        fakeApi = FakeGroupApi()
        repository = GroupRepository(fakeApi)
    }

    @Test
    fun `refreshGroups updates state flow`() = runTest {
        fakeApi.groups.add(Group(id = GroupId("group-1"), managerType = "round_robin", description = "Test", agentIds = listOf(AgentId("agent-1"))))

        repository.refreshGroups()

        assertEquals(1, repository.groups.first().size)
    }

    @Test
    fun `createGroup upserts cache`() = runTest {
        val created = repository.createGroup(GroupCreateParams(agentIds = listOf(AgentId("agent-1")), description = "Test group"))

        assertEquals("Test group", created.description)
        assertEquals(1, repository.groups.first().size)
    }

    @Test
    fun `updateGroup updates cache`() = runTest {
        fakeApi.groups.add(Group(id = GroupId("group-1"), managerType = "round_robin", description = "Old", agentIds = listOf(AgentId("agent-1"))))
        repository.refreshGroups()

        repository.updateGroup(GroupId("group-1"), GroupUpdateParams(description = "New"))

        assertEquals("New", repository.groups.first().first().description)
    }

    @Test
    fun `sendGroupMessage delegates to api`() = runTest {
        repository.sendGroupMessage(GroupId("group-1"), MessageCreateRequest(input = "hello"))

        assertTrue(fakeApi.calls.contains("sendGroupMessage:group-1"))
    }
}
