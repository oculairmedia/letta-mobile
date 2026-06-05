package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource.Companion.activeOnly
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagent
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagentStatus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        subagentAgentId: String? = null,
    ) = SubagentEntry(
        toolCallId = toolCallId,
        description = "desc $toolCallId",
        subagentType = "general-purpose",
        status = status,
        taskId = taskId,
        subagentAgentId = subagentAgentId,
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
    fun `subagentAgentId is carried through for view-conversation`() {
        // letta-mobile-vo9y1: present -> canViewConversation true.
        val mapped = entry("toolu_1", subagentAgentId = "agent-local-abc").toActiveSubagent()
        assertEquals("agent-local-abc", mapped.subagentAgentId)
        assertTrue(mapped.canViewConversation)

        // Absent / blank -> no affordance.
        assertNull(entry("toolu_2").toActiveSubagent().subagentAgentId)
        assertFalse(entry("toolu_3", subagentAgentId = "").toActiveSubagent().canViewConversation)
    }

    @Test
    fun `maps the full snapshot by replacement`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry("toolu_1"), entry("toolu_2")))
        val source = WsActiveSubagentSource(repo, backgroundScope, clock = { 0L })

        val mapped = source.activeSubagents.first { it.size == 2 }
        assertEquals(listOf("toolu_1", "toolu_2"), mapped.map { it.id })
        assertEquals(listOf("general-purpose", "general-purpose"), mapped.map { it.subagentType })

        // Replace the whole snapshot. The new RUNNING set replaces the old
        // one; the vanished entries linger briefly as completed terminals
        // (letta-mobile-29h9u). The running portion is the replacement only.
        repo.state.value = listOf(entry("toolu_3"))
        val after = source.activeSubagents.first { snapshot ->
            snapshot.any { it.id == "toolu_3" }
        }
        assertEquals(listOf("toolu_3"), after.filter { it.isActive }.map { it.id })
        // The previously-running entries are now lingering terminals, not gone.
        assertEquals(
            setOf("toolu_1", "toolu_2"),
            after.filter { it.isTerminal }.map { it.id }.toSet(),
        )
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
        // Pin the clock so the terminal entries are freshly stamped (not yet
        // expired) and would linger; activeOnly still strips them, proving the
        // strict rule is independent of the lingering one.
        val source = WsActiveSubagentSource(repo, backgroundScope, clock = { 0L })

        val active = source.activeSubagents.activeOnly().first { it.isNotEmpty() }
        assertEquals(listOf("toolu_1"), active.map { it.id })
    }

    @Test
    fun `terminal entry in snapshot is stamped and lingers`() = runTest {
        // letta-mobile-29h9u: a status flip to completed should keep the chip
        // visible (stamped with terminalAt) for review, not vanish.
        val repo = FakeSubagentRepository(listOf(entry("toolu_1", SubagentStatus.RUNNING)))
        var nowMs = 1_000L
        val source = WsActiveSubagentSource(repo, backgroundScope, clock = { nowMs })

        source.activeSubagents.first { it.singleOrNull()?.isActive == true }

        // Flip to completed at t=1000.
        repo.state.value = listOf(entry("toolu_1", SubagentStatus.COMPLETED))
        val lingering = source.activeSubagents.first { it.singleOrNull()?.isTerminal == true }
        assertEquals("toolu_1", lingering.single().id)
        assertEquals(ActiveSubagent.Status.COMPLETED, lingering.single().status)

        // Within the linger window the chip stays.
        val stillVisible = lingering.withLingeringTerminals(now = 1_000L + 2_000L)
        assertEquals(listOf("toolu_1"), stillVisible.map { it.id })

        // After the window it is gone.
        val gone = lingering.withLingeringTerminals(now = 1_000L + ActiveSubagent.TERMINAL_LINGER_MS)
        assertTrue(gone.isEmpty())
    }

    @Test
    fun `running entry that disappears is treated as completed and lingers`() = runTest {
        // The shim often DROPS a finished subagent from the next snapshot
        // rather than flipping its status; the source must still surface the
        // outcome so the user isn't left with a chip that just vanished.
        val repo = FakeSubagentRepository(listOf(entry("toolu_1", SubagentStatus.RUNNING)))
        val source = WsActiveSubagentSource(repo, backgroundScope, clock = { 5_000L })

        source.activeSubagents.first { it.singleOrNull()?.isActive == true }

        // toolu_1 disappears entirely.
        repo.state.value = emptyList()
        val lingering = source.activeSubagents.first { it.any { e -> e.isTerminal } }
        val done = lingering.single { it.id == "toolu_1" }
        assertEquals(ActiveSubagent.Status.COMPLETED, done.status)
        assertEquals(5_000L, done.terminalAt)
    }
}
