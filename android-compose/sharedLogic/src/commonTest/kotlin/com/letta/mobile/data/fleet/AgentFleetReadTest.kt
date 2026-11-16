package com.letta.mobile.data.fleet

import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentFleetReadTest {
    private val roster = (1..6).map { AgentId("a$it") }

    @Test
    fun valuesKeepRosterOrderWhateverOrderReadsFinishIn() = runTest {
        val read = AgentFleetReads.read(roster.reversed()) { agent ->
            repeat(agent.value.removePrefix("a").toInt()) { yield() }
            agent.value.uppercase()
        }
        assertEquals(roster.reversed().map { it to it.value.uppercase() }, read.values)
        assertTrue(read.failures.isEmpty())
    }

    @Test
    fun atMostTheParallelismReadsAreInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var peak = 0
        val result = async {
            AgentFleetReads.read(roster, parallelism = 2) {
                inFlight += 1
                peak = maxOf(peak, inFlight)
                gate.await()
                inFlight -= 1
            }
        }
        runCurrent()
        assertEquals(2, inFlight)
        gate.complete(Unit)
        assertEquals(roster.size, result.await().values.size)
        assertEquals(2, peak)
    }

    @Test
    fun oneAgentsFailureLeavesTheOthers() = runTest {
        val boom = IllegalStateException("a3 down")
        val read = AgentFleetReads.read(roster) { agent -> if (agent.value == "a3") throw boom else agent.value }
        assertEquals(roster.filterNot { it.value == "a3" }.map { it.value }, read.values.map { it.second })
        assertEquals(mapOf(AgentId("a3") to boom), read.failures)
        assertFalse(read.allFailed)
    }

    @Test
    fun onlyEveryReadFailingIsATotalFailure() = runTest {
        val read = AgentFleetReads.read(roster.take(2)) { agent -> error("${agent.value} down") }
        assertTrue(read.allFailed)
        assertEquals("a1 down", read.firstFailure?.message)
        assertFalse(AgentFleetReads.read(emptyList<AgentId>()) { it }.allFailed)
    }

    @Test
    fun aNullValueIsAnAnswerAndDuplicatesAreReadOnce() = runTest {
        val reads = mutableListOf<AgentId>()
        val read = AgentFleetReads.read(listOf(AgentId("a1"), AgentId("a1"))) { agent ->
            reads += agent
            null
        }
        assertEquals(listOf(AgentId("a1") to null), read.values)
        assertEquals(listOf(AgentId("a1")), reads)
    }

    @Test
    fun cancellationIsNotSwallowedAsAFailure() = runTest {
        assertFailsWith<CancellationException> {
            AgentFleetReads.read(roster) { throw CancellationException("stop") }
        }
    }
}
