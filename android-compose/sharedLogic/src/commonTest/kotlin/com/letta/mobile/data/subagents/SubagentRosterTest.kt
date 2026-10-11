package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-ulcg6 (PR4): the shared roster projection. Shapes follow the
 * live 2026-10-10 log and letta-code 0.34.8 snapshots.
 */
class SubagentRosterTest {
    private val now = 1_791_642_000_000L

    private fun entry(
        id: String,
        status: String = SubagentStatus.RUNNING,
        type: String = "General-purpose",
        agentId: String? = null,
        parentAgentId: String = PARENT,
        conversationId: String? = null,
        lastActivityAtMs: Long? = null,
    ) = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = type,
        status = status,
        subagentAgentId = agentId,
        subagentConversationId = conversationId,
        parentAgentId = parentAgentId,
        parentConversationId = "local-conv-517",
        startedAt = (now - 88_000L).toString(),
        lastActivityAtMs = lastActivityAtMs,
    )

    @Test
    fun splitsRunningFinishedAndBackgroundRuns() {
        val roster = projectSubagentRoster(
            listOf(
                entry("toolu_run"),
                entry("toolu_done", status = SubagentStatus.COMPLETED),
                entry("subagent-1791641853470-1", type = "Reflection"),
                entry("subagent-2", type = "Reflection integration", status = SubagentStatus.COMPLETED),
                // letta-code's memory git-conflict repair worker.
                entry("subagent-3", type = "Memory"),
            ),
            now = now,
        )
        assertEquals(listOf("toolu_run"), roster.running.map { it.key })
        assertEquals(listOf("toolu_done"), roster.finished.map { it.key })
        assertEquals(listOf("subagent-1791641853470-1", "subagent-2", "subagent-3"), roster.background.map { it.key })
        assertEquals(1, roster.runningCount)
    }

    @Test
    fun runningAffordanceCountsOnlyUserFacingWork() {
        val entries = listOf(
            entry("toolu_run"),
            entry("subagent-1791641853470-1", type = "Reflection"),
            entry("toolu_done", status = "success"),
        )
        assertEquals(1, entries.visibleRunningCount())
    }

    @Test
    fun quietIsAStateOfRunningNeverAFailure() {
        val roster = projectSubagentRoster(
            listOf(entry("toolu_quiet", lastActivityAtMs = now - QUIET_AFTER_MS - 1)),
            now = now,
        )
        val item = roster.running.single()
        assertEquals(SubagentRosterStatus.QUIET, item.status)
        assertFalse(item.status.isTerminal)
        assertTrue(roster.finished.isEmpty())
    }

    @Test
    fun silenceWithoutHostActivityTimeStaysRunning() {
        val item = projectSubagentRoster(listOf(entry("toolu_old_host")), now = now).running.single()
        assertEquals(SubagentRosterStatus.RUNNING, item.status)
    }

    @Test
    fun elapsedReadsEpochMillisAndIsoStartTimes() {
        val epoch = projectSubagentRoster(listOf(entry("toolu_epoch")), now = now).running.single()
        assertEquals(88_000L, epoch.elapsedMs)
        val iso = entry("toolu_iso").copy(startedAt = "2026-10-10T00:00:00Z")
        assertEquals(
            60_000L,
            projectSubagentRoster(listOf(iso), now = kotlin.time.Instant.parse("2026-10-10T00:01:00Z").toEpochMilliseconds())
                .running.single().elapsedMs,
        )
        assertEquals("1m 28s", formatSubagentElapsed(88_000L))
    }

    @Test
    fun nestedSubagentFollowsItsParentOneLevelDeep() {
        val roster = projectSubagentRoster(
            listOf(
                entry("toolu_grandchild", parentAgentId = "agent-local-child"),
                entry("toolu_other"),
                entry("toolu_child", agentId = "agent-local-child"),
            ),
            now = now,
        )
        assertEquals(listOf("toolu_other", "toolu_child", "toolu_grandchild"), roster.running.map { it.key })
        val grandchild = roster.running.last()
        assertEquals(1, grandchild.depth)
        assertEquals("toolu_child", grandchild.parentKey)
    }

    @Test
    fun externalCodingAgentsCarryTheirIdentity() {
        val roster = projectSubagentRoster(
            listOf(
                entry("toolu_cc", type = "Claude-code"),
                entry("toolu_cx", type = "Codex"),
                entry("claude_session_1", type = "General-purpose"),
                entry("toolu_letta"),
            ),
            now = now,
        )
        assertEquals(
            listOf(SubagentIdentity.CLAUDE_CODE, SubagentIdentity.CODEX, SubagentIdentity.CLAUDE_CODE, SubagentIdentity.LETTA),
            roster.running.map { it.identity },
        )
        assertEquals("Claude Code", SubagentIdentity.CLAUDE_CODE.label)
        assertNull(SubagentIdentity.LETTA.label)
    }

    @Test
    fun conversationOpensOnlyOnceTheChildConversationExists() {
        val roster = projectSubagentRoster(
            listOf(
                entry("toolu_running", agentId = "agent-local-a"),
                entry("toolu_done", status = "success", agentId = "agent-local-b", conversationId = "default"),
            ),
            now = now,
        )
        assertFalse(roster.running.single().canOpenConversation)
        assertTrue(roster.finished.single().canOpenConversation)
    }

    @Test
    fun clearedFinishedRowsStayHidden() {
        val roster = projectSubagentRoster(
            listOf(entry("toolu_done", status = SubagentStatus.FAILED)),
            now = now,
            clearedKeys = setOf("toolu_done"),
        )
        assertTrue(roster.finished.isEmpty())
    }

    @Test
    fun activityAndToolCountComeFromTheHost() {
        val item = projectSubagentRoster(
            listOf(
                entry("toolu_busy").copy(
                    toolCallCount = 12,
                    activity = SubagentActivitySnapshot(lines = listOf("Read src/A.kt", "Bash git status")),
                    model = "claude-sonnet-5-5",
                ),
            ),
            now = now,
        ).running.single()
        assertEquals(12, item.toolCallCount)
        assertEquals(listOf("Read src/A.kt", "Bash git status"), item.activity)
        assertEquals("claude-sonnet-5-5", item.model)
    }

    @Test
    fun rosterKeyPrefersToolCallThenTaskThenAComposite() {
        val base = SubagentEntry(toolCallId = "call_123", taskId = "task_456", description = "d", subagentType = "R", status = "running", startedAt = "t0")
        assertEquals("call_123", base.rosterKey())
        assertEquals("task_456", base.copy(toolCallId = "").rosterKey())
        assertEquals("t0_R_d", base.copy(toolCallId = "", taskId = "").rosterKey())
        assertTrue(base.copy(toolCallId = "", taskId = "", startedAt = "t1").rosterKey() != "t0_R_d")
    }

    private companion object {
        const val PARENT = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
    }
}
