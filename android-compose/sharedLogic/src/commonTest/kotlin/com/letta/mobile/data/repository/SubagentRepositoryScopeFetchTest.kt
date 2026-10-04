package com.letta.mobile.data.repository

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
