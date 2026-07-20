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
 * letta-mobile-73o2h.3: unit coverage for the WS-backed [ActiveSubagentSource]
 * mapping (wire [SubagentEntry] -> UI [ActiveSubagent]). No Android deps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class WsActiveSubagentSourceTest {

    @JvmInline private value class ToolCallRef(val value: String)
    @JvmInline private value class TaskRef(val value: String)
    @JvmInline private value class AgentRef(val value: String)
    @JvmInline private value class ConversationRef(val value: String)

    private data class EntryRefs(
        val toolCallId: ToolCallRef = ToolCallRef(""),
        val taskId: TaskRef? = null,
        val subagentAgentId: AgentRef? = null,
        val subagentConversationId: ConversationRef? = null,
        val parentAgentId: AgentRef = AgentRef("agent-parent"),
        val parentConversationId: ConversationRef = ConversationRef("default"),
    )

    private class FakeSubagentRepository(
        initial: List<SubagentEntry> = emptyList(),
    ) : ISubagentRepository {
        val state = MutableStateFlow(initial)
        var refreshResult: Result<List<SubagentEntry>>? = null
        var refreshCalls = 0
        override fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>> =
            state.map { entries -> entries.filter { it.inScope(scope) } }
        override fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry> =
            state.value.filter { it.inScope(scope) }
        private fun SubagentEntry.inScope(scope: SubagentParentScope): Boolean =
            parentAgentId == scope.parentAgentId && parentConversationId == scope.parentConversationId
        override suspend fun refresh(): Result<List<SubagentEntry>> {
            refreshCalls += 1
            return refreshResult ?: Result.success(state.value)
        }
        override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> = Result.success(emptyList())
    }

    private fun entry(
        refs: EntryRefs,
        status: String = SubagentStatus.RUNNING,
        terminalAtEpochMs: Long? = null,
    ) = SubagentEntry(
        toolCallId = refs.toolCallId.value,
        description = "desc ${refs.toolCallId.value}",
        subagentType = "general-purpose",
        status = status,
        taskId = refs.taskId?.value,
        subagentAgentId = refs.subagentAgentId?.value,
        subagentConversationId = refs.subagentConversationId?.value,
        parentAgentId = refs.parentAgentId.value,
        parentConversationId = refs.parentConversationId.value,
        terminalAtEpochMs = terminalAtEpochMs,
    )

    private fun source(
        repo: ISubagentRepository,
        scope: CoroutineScope,
        conversationId: MutableStateFlow<String?> = MutableStateFlow("default"),
    ) = WsActiveSubagentSource(repo, scope, "agent-parent", conversationId)

    @Test
    fun `status strings map to ActiveSubagent Status`() {
        assertEquals(ActiveSubagent.Status.RUNNING, SubagentStatus.RUNNING.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.COMPLETED, SubagentStatus.COMPLETED.toActiveSubagentStatus())
        assertEquals(ActiveSubagent.Status.FAILED, SubagentStatus.FAILED.toActiveSubagentStatus())
        // letta-mobile-drv4a: `cancelled` (killed / evicted / orphaned /
        // TaskStop'd / process gone) is terminal → maps to FAILED so the chip
        // lingers then dismisses instead of being stuck running.
        assertEquals(ActiveSubagent.Status.FAILED, SubagentStatus.CANCELLED.toActiveSubagentStatus())
        assertTrue(SubagentStatus.CANCELLED.toActiveSubagentStatus().isTerminal)
        // Forward-compat: unknown status keeps the chip running.
        assertEquals(ActiveSubagent.Status.RUNNING, "some_future_state".toActiveSubagentStatus())
    }

    @Test
    fun `id prefers toolCallId then falls back to taskId`() {
        assertEquals("toolu_1", entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))).toActiveSubagent().id)
        assertEquals("task_2", entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_2"))).toActiveSubagent().id)
    }

    @Test
    fun `entry kind is inferred from correlation keys`() {
        // letta-mobile-pvrrm: tool_call_id present -> a dispatched subagent.
        assertEquals(
            ActiveSubagent.Kind.SUBAGENT,
            entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))).toActiveSubagent().kind,
        )
        // Only a task_id (no tool_call_id) -> a background tool task.
        assertEquals(
            ActiveSubagent.Kind.BACKGROUND_TASK,
            entry(EntryRefs(toolCallId = ToolCallRef(""), taskId = TaskRef("task_2"))).toActiveSubagent().kind,
        )
    }

    @Test
    fun `startedAt seeds the lastUpdateAt baseline for the stuck heuristic`() {
        // letta-mobile-dvobc: a parseable ISO timestamp becomes the baseline.
        val withTs = SubagentEntry(
            toolCallId = "toolu_1",
            status = SubagentStatus.RUNNING,
            startedAt = "2026-06-05T00:00:00Z",
        ).toActiveSubagent()
        assertEquals(
            java.time.Instant.parse("2026-06-05T00:00:00Z").toEpochMilli(),
            withTs.lastUpdateAt,
        )
        // A malformed timestamp must not crash — it degrades to null.
        val bad = SubagentEntry(
            toolCallId = "toolu_2",
            status = SubagentStatus.RUNNING,
            startedAt = "not-a-date",
        ).toActiveSubagent()
        assertNull(bad.lastUpdateAt)
    }

    @Test
    fun `subagentAgentId is carried through for view-conversation`() {
        // letta-mobile-vo9y1: present -> canViewConversation true.
        val mapped = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()
        assertEquals("agent-local-abc", mapped.subagentAgentId)
        assertTrue(mapped.canViewConversation)

        // Absent / blank -> no affordance.
        assertNull(entry(EntryRefs(toolCallId = ToolCallRef("toolu_2"))).toActiveSubagent().subagentAgentId)
        assertFalse(entry(EntryRefs(toolCallId = ToolCallRef("toolu_3"), subagentAgentId = AgentRef(""))).toActiveSubagent().canViewConversation)
    }

    @Test
    fun `resolveConversationId uses existing actual conversation id without refresh`() = runTest {
        val repo = FakeSubagentRepository()
        val source = source(repo, backgroundScope)
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
        val source = source(repo, backgroundScope)
        val subagent = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()

        assertEquals("conv-subagent-456", source.resolveConversationId(subagent).getOrNull())
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `resolveConversationId returns null instead of default when lookup fails`() = runTest {
        val repo = FakeSubagentRepository()
        repo.refreshResult = Result.success(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_other"), subagentConversationId = ConversationRef("default")))))
        val source = source(repo, backgroundScope)
        val subagent = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"), subagentAgentId = AgentRef("agent-local-abc"))).toActiveSubagent()

        assertNull(source.resolveConversationId(subagent).getOrNull())
        assertEquals(1, repo.refreshCalls)
    }

    @Test
    fun `maps each durable repository snapshot without local lifecycle state`() = runTest {
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1"))), entry(EntryRefs(toolCallId = ToolCallRef("toolu_2")))))
        val source = source(repo, backgroundScope)

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
        val source = source(repo, backgroundScope)

        val active = source.activeSubagents.activeOnly().first { it.isNotEmpty() }
        assertEquals(listOf("toolu_1"), active.map { it.id })
    }

    @Test
    fun `terminal entry in snapshot is stamped and lingers`() = runTest {
        // letta-mobile-29h9u: a status flip to completed should keep the chip
        // visible (stamped with terminalAt) for review, not vanish.
        val repo = FakeSubagentRepository(listOf(entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")), status = SubagentStatus.RUNNING)))
        var nowMs = 1_000L
        val source = source(repo, backgroundScope)

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
        val source = source(repo, backgroundScope)

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
        val source = source(repo, backgroundScope)

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
        val source = source(repo, backgroundScope, conversationId)

        assertEquals(listOf("toolu-a"), source.activeSubagents.first { it.isNotEmpty() }.map { it.id })
        conversationId.value = "conv-b"
        assertEquals(listOf("toolu-b"), source.activeSubagents.first { it.singleOrNull()?.id == "toolu-b" }.map { it.id })
    }

    @Test
    fun `source starts from durable terminal cache without empty flash`() = runTest {
        val terminal = entry(EntryRefs(toolCallId = ToolCallRef("toolu-terminal")), status = SubagentStatus.COMPLETED, terminalAtEpochMs = 1_000L)
        val repo = FakeSubagentRepository(listOf(terminal))

        val recreated = source(repo, backgroundScope)

        assertEquals(listOf("toolu-terminal"), recreated.activeSubagents.value.map { it.id })
        assertEquals(1_000L, recreated.activeSubagents.value.single().terminalAt)
    }

    // ── i2f23: todo_progress mapping ──────────────────────────────────────

    @Test
    fun `todo_progress is mapped from wire to UI progress model`() {
        val wire = SubagentEntry(
            toolCallId = "toolu_1",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 3, total = 7),
        )
        val mapped = wire.toActiveSubagent()

        assertNotNull(mapped.progress)
        assertEquals(3, mapped.progress?.completed)
        assertEquals(7, mapped.progress?.total)
        assertEquals(3f / 7f, mapped.ringFraction)
        assertTrue(mapped.hasDeterminateProgress)
    }

    @Test
    fun `null todo_progress maps to null progress on ActiveSubagent`() {
        val wire = entry(EntryRefs(toolCallId = ToolCallRef("toolu_1")))
        // entry() helper doesn't set todoProgress, so it defaults to null
        val mapped = wire.toActiveSubagent()

        assertNull(wire.todoProgress)
        assertEquals(null, mapped.progress)
        assertFalse(mapped.hasDeterminateProgress)
        assertEquals(0f, mapped.ringFraction)
    }

    @Test
    fun `todo_progress with zero total maps to determinate progress`() {
        val wire = SubagentEntry(
            toolCallId = "toolu_z",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 0, total = 0),
        )
        val mapped = wire.toActiveSubagent()

        assertNotNull(mapped.progress)
        assertEquals(0, mapped.progress?.completed)
        assertEquals(0, mapped.progress?.total)
        // Total 0 → hasDeterminateProgress is false (no meaningful fraction).
        assertFalse(mapped.hasDeterminateProgress)
        assertEquals(0f, mapped.ringFraction)
    }
}
