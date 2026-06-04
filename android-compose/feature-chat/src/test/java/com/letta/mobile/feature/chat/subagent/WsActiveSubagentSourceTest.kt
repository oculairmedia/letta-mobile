package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource.Companion.activeOnly
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagent
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagentStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * letta-mobile-73o2h.3: unit coverage for the WS-backed [ActiveSubagentSource]
 * mapping (wire [SubagentEntry] -> UI [ActiveSubagent]). No Android deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class WsActiveSubagentSourceTest {

    private class FakeSubagentRepository(
        initial: List<SubagentEntry> = emptyList(),
    ) : ISubagentRepository {
        val state = MutableStateFlow(initial)
        override fun activeSubagentsFlow(): Flow<List<SubagentEntry>> = state
        override suspend fun refresh(): Result<List<SubagentEntry>> = Result.success(state.value)
        override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> = Result.success(emptyList())
    }

    private fun entry(
        toolCallId: String,
        status: String = SubagentStatus.RUNNING,
        taskId: String? = null,
    ) = SubagentEntry(
        toolCallId = toolCallId,
        description = "desc $toolCallId",
        subagentType = "general-purpose",
        status = status,
        taskId = taskId,
    )

    @Test
    fun `status strings map to ActiveSubagent Status`() {
        assertEquals(ActiveSubagent.Status.RUNNING, SubagentStatus.RUNNING.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.COMPLETED, SubagentStatus.COMPLETED.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.FAILED, SubagentStatus.FAILED.toActiveSubagentStatus())
        // Forward-compat: unknown status keeps the chip running.
        assertEquals(ActiveSubagent.Status.RUNNING, "some_future_state".toActiveSubagentStatus())
    }

    @Test
    fun `id prefers toolCallId then falls back to taskId`() {
        assertEquals("toolu_1", entry("toolu_1").toActiveSubagent().id)
        assertEquals("task_2", entry("", taskId = "task_2").toActiveSubagent().id)
    }

    @Test
    fun `maps the full snapshot by replacement`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry("toolu_1"), entry("toolu_2")))
        val source = WsActiveSubagentSource(repo, backgroundScope)

        val mapped = source.activeSubagents.first { it.size == 2 }
        assertEquals(listOf("toolu_1", "toolu_2"), mapped.map { it.id })
        assertEquals(listOf("general-purpose", "general-purpose"), mapped.map { it.subagentType })

        // Replace the whole snapshot — emitted as a new full list.
        repo.state.value = listOf(entry("toolu_3"))
        val after = source.activeSubagents.first { it.singleOrNull()?.id == "toolu_3" }
        assertEquals(listOf("toolu_3"), after.map { it.id })
    }

    @Test
    fun `terminal entries are dropped by the activeOnly host rule`() = runTest {
        val repo = FakeSubagentRepository(
            listOf(
                entry("toolu_1", SubagentStatus.RUNNING),
                entry("toolu_2", SubagentStatus.COMPLETED),
                entry("toolu_3", SubagentStatus.FAILED),
            ),
        )
        val source = WsActiveSubagentSource(repo, backgroundScope)

        val active = source.activeSubagents.activeOnly().first { it.isNotEmpty() }
        assertEquals(listOf("toolu_1"), active.map { it.id })
    }
}
