package com.letta.mobile.feature.chat.screen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * letta-mobile-by4vm.47: the drawer's context-card session (compaction and context-limit
 * controllers) is built once per session graph, not again when the graph StateFlow replays its
 * current value to a subscriber.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentContextCardSessionTest {
    private class Graph(val name: String)
    private class Session(val graph: Graph)

    @Test
    fun theInitialGraphIsBuiltOnceAcrossItsReplay() = runTest {
        val first = Graph("first")
        val graphs = MutableStateFlow(first)
        val builds = mutableListOf<String>()
        val sessions = perGraph(graphs, backgroundScope) { graph -> builds += graph.name; Session(graph) }
        val initial = sessions.value

        val collector = backgroundScope.launch { sessions.collect {} }
        runCurrent()

        assertEquals(listOf("first"), builds)
        assertSame(initial, sessions.value)
        collector.cancel()
    }

    @Test
    fun aNewGraphBuildsOnceAndAResubscriptionReusesIt() = runTest {
        val graphs = MutableStateFlow(Graph("first"))
        val builds = mutableListOf<String>()
        val sessions = perGraph(graphs, backgroundScope) { graph -> builds += graph.name; Session(graph) }
        var collector: Job = backgroundScope.launch { sessions.collect {} }
        runCurrent()

        val second = Graph("second")
        graphs.value = second
        runCurrent()
        val afterSwitch = sessions.value
        assertSame(second, afterSwitch.graph)

        // Every subscriber leaves past the stop timeout, then one returns on the same graph.
        collector.cancel()
        advanceTimeBy(10_000L)
        runCurrent()
        collector = backgroundScope.launch { sessions.collect {} }
        runCurrent()

        assertEquals(listOf("first", "second"), builds)
        assertSame(afterSwitch, sessions.value)
        collector.cancel()
    }
}
