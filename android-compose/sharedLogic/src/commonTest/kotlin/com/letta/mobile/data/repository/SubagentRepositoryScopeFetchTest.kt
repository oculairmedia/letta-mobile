package com.letta.mobile.data.repository

import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.data.repository.ScopedSubagentListTransport.Companion.PARENT_AGENT
import com.letta.mobile.data.repository.ScopedSubagentListTransport.Companion.running
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-fxoew.5: the host's `subagent.list` is conversation-scoped, so
 * every distinct parent scope needs its own fetch, and a failed fetch must be
 * retried by the next collection instead of leaving the scope empty forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentRepositoryScopeFetchTest {
    private val scopeA = SubagentParentScope(PARENT_AGENT, CONVERSATION_A)
    private val scopeB = SubagentParentScope(PARENT_AGENT, CONVERSATION_B)
    private val transport = ScopedSubagentListTransport().apply {
        entriesByConversation[CONVERSATION_A] = listOf(running("toolu_a", CONVERSATION_A))
        entriesByConversation[CONVERSATION_B] = listOf(running("toolu_b", CONVERSATION_B))
    }

    private var now = 1_000L

    private fun repository(owner: CoroutineScope) = SubagentRepository(
        transport = transport,
        scope = owner,
        clock = { now },
        streamTimeoutSweepIntervalMs = Long.MAX_VALUE,
    )

    @Test
    fun secondConversationIsFetchedWhenFirstRequested() = runTest {
        val repo = repository(backgroundScope)

        val first = withTimeout(5.seconds) { repo.activeSubagentsFlow(scopeA).first { it.isNotEmpty() } }
        val second = withTimeout(5.seconds) { repo.activeSubagentsFlow(scopeB).first { it.isNotEmpty() } }

        assertEquals(listOf("toolu_a"), first.map { it.toolCallId })
        assertEquals(listOf("toolu_b"), second.map { it.toolCallId })
        assertEquals(listOf(CONVERSATION_A, CONVERSATION_B), transport.requestedConversations)
        repo.close()
    }

    @Test
    fun scopedFetchNeverEvictsAnotherConversationsRunningEntry() = runTest {
        val repo = repository(backgroundScope)
        repo.activeSubagentsFlow(scopeA).first { it.isNotEmpty() }
        backgroundScope.launch { repo.activeSubagentsFlow(scopeB).collect {} }
        runCurrent()

        // Re-list conversation B well past the running-absence linger.
        now += RUNNING_ABSENCE_LINGER_PLUS_ONE_MS
        reconnect()

        assertEquals(listOf("toolu_a"), repo.currentActiveSubagents(scopeA).map { it.toolCallId })
        assertEquals(listOf(CONVERSATION_A, CONVERSATION_B, CONVERSATION_B), transport.requestedConversations)
        repo.close()
    }

    @Test
    fun dismissedPushRetiresOnlyItsExactIdentityAndRejectsLateRunning() = runTest {
        val first = running("toolu_a", CONVERSATION_A).copy(description = "same title")
        val second = running("toolu_b", CONVERSATION_A).copy(description = "same title")
        transport.entriesByConversation[CONVERSATION_A] = listOf(first, second)
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        runCurrent()
        assertEquals(2, repo.currentActiveSubagents(scopeA).size)
        val dismissed = kotlinx.serialization.json.Json.decodeFromString<com.letta.mobile.data.model.SubagentEntry>(
            "{\"toolCallId\":\"toolu_a\",\"status\":\"cancelled\"," +
                "\"parentAgentId\":\"agent-parent\",\"parentConversationId\":\"conv-a\"," +
                "\"terminal_reason\":\"user_dismissed\"}",
        )
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "dismissed", ts = "t", reason = "registry", subagentsActive = listOf(dismissed, second),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals(listOf("toolu_b"), repo.currentActiveSubagents(scopeA).map { it.toolCallId })
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "late", ts = "t", reason = "registry", subagentsActive = listOf(first, second),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals(listOf("toolu_b"), repo.currentActiveSubagents(scopeA).map { it.toolCallId })
        repo.close()
    }

    @Test
    fun receiptAndNotificationInEitherOrderRetireTheSameScopedTask() = runTest {
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        backgroundScope.launch { repo.activeSubagentsFlow(scopeB).collect {} }
        runCurrent()
        transport.pushBeforeReceipt = true
        repo.dismiss(scopeA, ToolCallId("toolu_a")).getOrThrow()
        runCurrent()
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeA))
        assertEquals(listOf("toolu_b"), repo.currentActiveSubagents(scopeB).map { it.toolCallId })
        transport.pushBeforeReceipt = false
        repo.dismiss(scopeB, ToolCallId("toolu_b")).getOrThrow()
        val late = running("toolu_b", CONVERSATION_B)
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "terminal-after-receipt", ts = "t", reason = "registry",
            subagentsActive = listOf(late.copy(status = "cancelled", terminalReason = "user_dismissed")),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_B,
        ))
        runCurrent()
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "after-receipt", ts = "t", reason = "registry", subagentsActive = listOf(late),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_B,
        ))
        runCurrent()
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeB))
        repo.close()
    }

    @Test
    fun mismatchedPushScopeCannotDismissAnotherConversation() = runTest {
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        runCurrent()
        val wrongScopeTerminal = running("toolu_a", CONVERSATION_A).copy(status = "cancelled", terminalReason = "user_dismissed")
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "wrong-scope", ts = "t", reason = "registry", subagentsActive = listOf(wrongScopeTerminal),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_B,
        ))
        runCurrent()
        assertEquals(listOf("toolu_a"), repo.currentActiveSubagents(scopeA).map { it.toolCallId })
        repo.close()
    }

    @Test
    fun scopedReloadLearnsDismissalBeforeLateRunningSnapshot() = runTest {
        val running = running("toolu_a", CONVERSATION_A)
        transport.entriesByConversation[CONVERSATION_A] = listOf(
            running.copy(status = "cancelled", terminalReason = "user_dismissed"),
        )
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        runCurrent()
        assertEquals(listOf(CONVERSATION_A), transport.requestedConversations)
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeA))
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "late-after-reload", ts = "t", reason = "registry", subagentsActive = listOf(running),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeA))
        repo.close()
    }

    @Test
    fun separatorCharactersInDifferentScopesCannotCollide() = runTest {
        val firstScope = SubagentParentScope("a|b", "c")
        val secondScope = SubagentParentScope("a", "b|c")
        transport.entriesByConversation["c"] = listOf(running("tool", "c").copy(parentAgentId = "a|b"))
        transport.entriesByConversation["b|c"] = listOf(running("tool", "b|c").copy(parentAgentId = "a"))
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(firstScope).collect {} }
        backgroundScope.launch { repo.activeSubagentsFlow(secondScope).collect {} }
        runCurrent()
        assertEquals(1, repo.currentActiveSubagents(firstScope).size)
        assertEquals(1, repo.currentActiveSubagents(secondScope).size)
        repo.close()
    }

    @Test
    fun terminalOutcomeDoesNotResurrectAfterTheDisplayLinger() = runTest {
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        runCurrent()
        val running = running("toolu_a", CONVERSATION_A)
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "terminal", ts = "t", reason = "registry", subagentsActive = listOf(running.copy(status = "completed")),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals("completed", repo.currentActiveSubagents(scopeA).single().status)
        now += 8_001L
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "after-linger", ts = "t", reason = "registry", subagentsActive = emptyList(),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeA))
        transport.events.emit(com.letta.mobile.data.transport.ServerFrame.SubagentsUpdated(
            id = "late-running", ts = "t", reason = "registry", subagentsActive = listOf(running),
            parentAgentId = PARENT_AGENT, parentConversationId = CONVERSATION_A,
        ))
        runCurrent()
        assertEquals(emptyList(), repo.currentActiveSubagents(scopeA))
        repo.close()
    }

    private fun TestScope.reconnect() {
        transport.state.value = ChannelTransportState.Disconnected(1000, "drop")
        runCurrent()
        transport.state.value = ScopedSubagentListTransport.connected()
        runCurrent()
    }

    @Test
    fun repeatedCollectionOfOneScopeFetchesOnce() = runTest {
        val repo = repository(backgroundScope)

        repeat(3) { repo.activeSubagentsFlow(scopeA).first { it.isNotEmpty() } }

        assertEquals(listOf(CONVERSATION_A), transport.requestedConversations)
        repo.close()
    }

    @Test
    fun failedFetchIsRetriedByNextCollection() = runTest {
        transport.failuresBeforeSuccess = 1
        val repo = repository(backgroundScope)

        assertEquals(emptyList(), repo.activeSubagentsFlow(scopeA).first())
        runCurrent()
        assertEquals(1, transport.requestedConversations.size)

        val retried = withTimeout(5.seconds) { repo.activeSubagentsFlow(scopeA).first { it.isNotEmpty() } }

        assertEquals(listOf("toolu_a"), retried.map { it.toolCallId })
        assertEquals(listOf(CONVERSATION_A, CONVERSATION_A), transport.requestedConversations)
        repo.close()
    }

    @Test
    fun reconnectRefetchesCollectedScope() = runTest {
        val repo = repository(backgroundScope)
        backgroundScope.launch { repo.activeSubagentsFlow(scopeA).collect {} }
        runCurrent()
        assertEquals(1, transport.requestedConversations.size)

        reconnect()

        assertEquals(listOf(CONVERSATION_A, CONVERSATION_A), transport.requestedConversations)
        repo.close()
    }

    private companion object {
        const val CONVERSATION_A = "conv-a"
        const val CONVERSATION_B = "conv-b"
        const val RUNNING_ABSENCE_LINGER_PLUS_ONE_MS = 60_001L
    }
}
