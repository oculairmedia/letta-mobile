package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.model.SubagentTodoProgressWire
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource.Companion.activeOnly
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagent
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource.Companion.toActiveSubagentStatus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Flow / scope / linger coverage for [WsActiveSubagentSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class WsActiveSubagentSourceFlowTest {

    @Test
    fun `resolveConversationId uses existing actual conversation id without refresh`() = runTest {
        val repo = FakeSubagentRepository()
        val source = wsActiveSubagentSource(repo, backgroundScope)
        val subagent = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"), subagentConversationId = ConversationRef("conv-subagent-123"))).toActiveSubagent()

        assertEquals("conv-subagent-123", source.resolveConversationId(subagent).getOrNull())
        assertEquals(0, repo.refreshCalls)
    }


    @Test
    fun `resolveConversationId refresh lookup finds actual conversation id by tool call`() = runTest {
        val repo = FakeSubagentRepository()
        repo.refreshResult = Result.success(
            listOf(
                entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"), subagentConversationId = ConversationRef("conv-subagent-456"))),
            ),
        )
        val source = wsActiveSubagentSource(repo, backgroundScope)
        val subagent = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()

        assertEquals("conv-subagent-456", source.resolveConversationId(subagent).getOrNull())
        assertEquals(1, repo.refreshCalls)
    }


    @Test
    fun `resolveConversationId returns null instead of default when lookup fails`() = runTest {
        val repo = FakeSubagentRepository()
        repo.refreshResult = Result.success(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_other"), subagentConversationId = ConversationRef("default")))))
        val source = wsActiveSubagentSource(repo, backgroundScope)
        val subagent = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()

        assertNull(source.resolveConversationId(subagent).getOrNull())
        assertEquals(1, repo.refreshCalls)
    }


    @Test
    fun `maps each durable repository snapshot without local lifecycle state`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))), entry(EntryRefs(toolCallId = ToolCallRef("toolu_2")))))
        val source = wsActiveSubagentSource(repo, backgroundScope)

        val mapped = source.activeSubagents.first { it.size == 2 }
        assertEquals(listOf("toolu_1", "toolu_2"), mapped.map { it.id })
        assertEquals(listOf("general-purpose", "general-purpose"), mapped.map { it.subagentType })

        // The durable repository owns omission retention. This source only
        // projects the already-reduced, parent-scoped snapshot.
        repo.state.value = listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_3"))))
        val after = source.activeSubagents.first { snapshot ->
            snapshot.any { it.id == "toolu_3" }
        }
        assertEquals(setOf("toolu_3"), after.filter { it.isActive }.map { it.id }.toSet())
        assertTrue(after.none { it.isTerminal })
    }


    @Test
    fun `terminal entries are dropped by the activeOnly host rule`() = runTest {
        val repo = FakeSubagentRepository(
            listOf(
                entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.RUNNING),
                entry(EntryRefs(toolCallId = ToolCallRef("toolu_2")), status = SubagentStatus.COMPLETED),
                entry(EntryRefs(toolCallId = ToolCallRef("toolu_3")), status = SubagentStatus.FAILED),
            ),
        )
        // Pin the clock so the terminal entries are freshly stamped (not yet
        // expired) and would linger; activeOnly still strips them, proving the
        // strict rule is independent of the lingering one.
        val source = wsActiveSubagentSource(repo, backgroundScope)

        val active = source.activeSubagents.activeOnly().first { it.isNotEmpty() }
        assertEquals(listOf("toolu_1"), active.map { it.id })
    }


    @Test
    fun `terminal entry in snapshot is stamped and lingers`() = runTest {
        // letta-mobile-29h9u: a status flip to completed should keep the chip
        // visible (stamped with terminalAt) for review, not vanish.
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.RUNNING)))
        var nowMs = 1_000L
        val source = wsActiveSubagentSource(repo, backgroundScope)

        source.activeSubagents.first { it.singleOrNull()?.isActive == true }

        // Flip to completed at t=1000.
        repo.state.value = listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.COMPLETED, terminalAtEpochMs = nowMs))
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
    fun `explicit terminal state clears retained running entry into linger`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.RUNNING)))
        val source = wsActiveSubagentSource(repo, backgroundScope)

        source.activeSubagents.first { it.singleOrNull()?.isActive == true }

        repo.state.value = listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.COMPLETED, terminalAtEpochMs = 5_000L))
        val lingering = source.activeSubagents.first { it.any { e -> e.isTerminal } }
        val done = lingering.single { it.id == "toolu_1" }
        assertEquals(ActiveSubagent.Status.COMPLETED, done.status)
        assertEquals(5_000L, done.terminalAt)
    }


    @Test
    fun `omitted background task id remains visible until explicit terminal`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_1")))))
        val source = wsActiveSubagentSource(repo, backgroundScope)

        val initial = source.activeSubagents.first { it.singleOrNull()?.id == "task_1" }
        assertEquals(ActiveSubagent.Kind.BACKGROUND_TASK, initial.single().kind)
        assertTrue(initial.single().isActive)

        repo.state.value = emptyList()
        val retained = source.activeSubagents.first { snapshot ->
            snapshot.singleOrNull()?.id == "task_1" && snapshot.single().isActive
        }
        assertEquals(listOf("task_1"), retained.map { it.id })

        repo.state.value = listOf(entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_1")), status = SubagentStatus.COMPLETED, terminalAtEpochMs = 9_000L))
        val terminal = source.activeSubagents.first { snapshot ->
            snapshot.singleOrNull()?.id == "task_1" && snapshot.single().isTerminal
        }
        assertEquals(ActiveSubagent.Status.COMPLETED, terminal.single().status)
    }


    @Test
    fun `conversation switch does not retain chips from previous scope`() = runTest {
        val repo = FakeSubagentRepository(
            listOf(
                entry(EntryRefs(toolCallId = ToolCallRef("toolu-a"), parentConversationId = ConversationRef("conv-a"))),
                entry(EntryRefs(toolCallId = ToolCallRef("toolu-b"), parentConversationId = ConversationRef("conv-b"))),
            )
        )
        val conversationId = MutableStateFlow<String?>("conv-a")
        val source = wsActiveSubagentSource(repo, backgroundScope, conversationId)

        assertEquals(listOf("toolu-a"), source.activeSubagents.first { it.isNotEmpty() }.map { it.id })
        conversationId.value = "conv-b"
        assertEquals(listOf("toolu-b"), source.activeSubagents.first { it.singleOrNull()?.id == "toolu-b" }.map { it.id })
    }


    @Test
    fun `source starts from durable terminal cache without empty flash`() = runTest {
        val terminal = entry(EntryRefs(toolCallId = ToolCallRef("toolu-terminal")), status = SubagentStatus.COMPLETED, terminalAtEpochMs = 1_000L)
        val repo = FakeSubagentRepository(listOf(terminal))

        val recreated = wsActiveSubagentSource(repo, backgroundScope)

        assertEquals(listOf("toolu-terminal"), recreated.activeSubagents.value.map { it.id })
        assertEquals(1_000L, recreated.activeSubagents.value.single().terminalAt)
    }

    // ── i2f23: todo_progress mapping ──────────────────────────────────────

}
