package com.letta.mobile.data.repository

import com.letta.mobile.data.api.GroupApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupListParams
import com.letta.mobile.data.model.GroupMessagesListParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.testutil.TestMessageFactory
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for
 * [GroupRepository.refreshGroups] (admin, exhaust all) and
 * [GroupRepository.listGroupMessages] (bounded `maxPages = 20`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class GroupPaginationTest {

    @Test
    fun `refreshGroups fetches both pages when API returns exactly two`() = runTest {
        val groups = (1..75).map {
            Group(
                id = GroupId("group-$it"),
                managerType = "round_robin",
                agentIds = listOf(AgentId("a-$it")),
                description = "desc-$it",
            )
        }
        val api = PaginatingGroupApi(groups)
        val repo = GroupRepository(api)

        repo.refreshGroups()

        assertEquals(75, repo.groups.value.size)
        assertEquals(listOf<String?>(null, "group-50"), api.observedAfters)
        assertEquals(listOf(50, 50), api.observedLimits)
    }

    @Test
    fun `listGroupMessages is bounded and fetches both pages when API returns exactly two`() = runTest {
        val messages = (1..60).map { TestMessageFactory.userMessage(id = "m-$it", content = "msg-$it") }
        val api = PaginatingGroupApi(groups = emptyList(), messages = messages)
        val repo = GroupRepository(api)

        val result = repo.listGroupMessages(GroupId("group-1"))

        assertEquals(60, result.size)
        assertEquals(2, api.observedAftersForMessages.size)
        assertEquals(null, api.observedAftersForMessages[0])
        assertEquals("m-50", api.observedAftersForMessages[1])
    }

    private class PaginatingGroupApi(
        private val groups: List<Group>,
        private val messages: List<LettaMessage> = listOf(TestMessageFactory.userMessage(id = "m-1", content = "x")),
    ) : GroupApi(mockk(relaxed = true)) {
        val observedAfters = mutableListOf<String?>()
        val observedLimits = mutableListOf<Int?>()
        val observedAftersForMessages = mutableListOf<String?>()

        override suspend fun listGroups(params: GroupListParams): List<Group> {
            observedAfters += params.after
            observedLimits += params.limit
            val pageSize = params.limit ?: 50
            val start = params.after?.let { id ->
                groups.indexOfFirst { it.id.value == id }.let { if (it < 0) groups.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(groups.size)
            return groups.subList(start, end)
        }

        override suspend fun listGroupMessages(params: GroupMessagesListParams): List<LettaMessage> {
            observedAftersForMessages += params.after
            val pageSize = params.limit ?: 50
            val start = params.after?.let { id ->
                messages.indexOfFirst { it.id == id }.let { if (it < 0) messages.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(messages.size)
            return messages.subList(start, end)
        }
    }
}