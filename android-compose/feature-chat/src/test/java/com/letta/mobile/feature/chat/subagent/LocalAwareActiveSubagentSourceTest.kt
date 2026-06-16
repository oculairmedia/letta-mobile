package com.letta.mobile.feature.chat.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-7vs4s: the local-aware gate must suppress shim subagent chips
 * while the agent is local-bound (no shim WS feed → phantom perpetual chips),
 * and pass the real registry through on remote, reacting to binding changes.
 */
class LocalAwareActiveSubagentSourceTest {

    private fun sample(id: String) = ActiveSubagent(
        id = id,
        description = "d-$id",
        subagentType = "general-purpose",
        status = ActiveSubagent.Status.RUNNING,
    )

    @Test
    fun `local-bound suppresses chips to empty`() = runTest {
        val delegate = FakeActiveSubagentSource(listOf(sample("a"), sample("b")))
        val localBound = MutableStateFlow(true)
        val source = LocalAwareActiveSubagentSource(delegate, localBound, backgroundScope)
        backgroundScope.launch { source.activeSubagents.collect {} }
        // keep the WhileSubscribed flow hot
        testScheduler.advanceUntilIdle()

        assertTrue("local-bound must show no chips", source.activeSubagents.value.isEmpty())
    }

    @Test
    fun `remote passes the real registry through`() = runTest {
        val delegate = FakeActiveSubagentSource(listOf(sample("a"), sample("b")))
        val localBound = MutableStateFlow(false)
        val source = LocalAwareActiveSubagentSource(delegate, localBound, backgroundScope)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { source.activeSubagents.collect { seen.add(it.map { s -> s.id }) } }
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "b"), seen.last())
    }

    @Test
    fun `flips live when binding changes remote then local`() = runTest {
        val delegate = FakeActiveSubagentSource(listOf(sample("a")))
        val localBound = MutableStateFlow(false)
        val source = LocalAwareActiveSubagentSource(delegate, localBound, backgroundScope)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { source.activeSubagents.collect { seen.add(it.map { s -> s.id }) } }
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a"), seen.last())

        localBound.value = true
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertTrue("switching to local must clear chips", seen.last().isEmpty())
    }
}
