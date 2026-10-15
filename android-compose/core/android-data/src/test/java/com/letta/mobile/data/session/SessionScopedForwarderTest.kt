package com.letta.mobile.data.session

import com.letta.mobile.data.model.SelfTodoSnapshot
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Something with a default the implementation overrides, as IAgentRepository.listAgentSummaries. */
interface ForwarderProbe {
    val state: StateFlow<String>
    fun label(): String = "interface default"
    suspend fun load(key: String): String = "interface default"
}

interface SharedFlowProbe {
    val events: SharedFlow<String>
}

/** letta-mobile-by4vm.88: [sessionScoped] rules in isolation (the repositories have their own tests). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedForwarderTest {

    private class Probe(name: String) : ForwarderProbe {
        override val state = MutableStateFlow(name)
        override fun label() = state.value
        override suspend fun load(key: String) = "${state.value}:$key"
    }

    private class Graphs<R>(vararg repositories: R) : SessionRepositoryGraphProvider<SessionRepositoryGraph> {
        private val byGraph = repositories.associateBy { mockk<SessionRepositoryGraph>() }
        private val order = byGraph.keys.toList()
        override val currentGraph = MutableStateFlow(order.first())
        override val sessionError = MutableStateFlow<Throwable?>(null)
        override val current: SessionRepositoryGraph get() = currentGraph.value
        override fun rebuild(): SessionRepositoryGraph = current

        override suspend fun <T> withCurrentSession(block: suspend (SessionRepositoryGraph) -> T): T {
            val graph = current
            return block(graph).also { if (current !== graph) throw CancellationException("Session switched during operation") }
        }

        fun repository(graph: SessionRepositoryGraph): R = byGraph.getValue(graph)
        fun switchTo(index: Int) {
            currentGraph.value = order[index]
        }
    }

    @Test
    fun defaultsForwardToTheImplementationAndCallsFollowTheSwitch() = runTest {
        val graphs = Graphs(Probe("a"), Probe("b"))
        val probe = graphs.sessionScoped(ForwarderProbe::class, select = graphs::repository)

        assertEquals("a", probe.label())
        assertEquals("a:k", probe.load("k"))
        graphs.switchTo(1)
        assertEquals("b", probe.label())
        assertEquals("b:k", probe.load("k"))
    }

    @Test
    fun stateFlowIsOneInstanceThatReadsAndFollowsTheCurrentGraph() = runTest {
        val graphs = Graphs(Probe("a"), Probe("b"))
        val probe = graphs.sessionScoped(ForwarderProbe::class, select = graphs::repository)
        val state = probe.state

        assertSame(state, probe.state)
        assertEquals("a", state.value)
        graphs.switchTo(1)
        assertEquals("b", state.value)
        assertEquals("b", state.first())
    }

    @Test
    fun selfTodoSessionSwitchEmitsOneConsistentSnapshot() = runTest {
        val old = FakeSelfTodoRepository(snapshot("old todo", "running"))
        val next = FakeSelfTodoRepository(snapshot("new todo", "completed"))
        val graphs = Graphs<ISelfTodoRepository>(old, next)
        val repository = graphs.sessionScoped(ISelfTodoRepository::class, select = graphs::repository)

        assertEquals(old.snapshot, repository.snapshotForFlow(CONVERSATION_ID).first())
        graphs.switchTo(1)
        assertEquals(next.snapshot, repository.snapshotForFlow(CONVERSATION_ID).first { it == next.snapshot })
        assertEquals(next.snapshot, repository.snapshotFor(CONVERSATION_ID))
    }

    @Test
    fun identityAndRejectedShapes() {
        val graphs = Graphs(Probe("a"))
        val probe = graphs.sessionScoped(ForwarderProbe::class, select = graphs::repository)
        val other = graphs.sessionScoped(ForwarderProbe::class, select = graphs::repository)

        assertEquals(probe, probe)
        assertNotEquals(probe, other)
        assertTrue(probe.toString().contains("ForwarderProbe"))
        assertThrows(IllegalArgumentException::class.java) {
            Graphs(mockk<SharedFlowProbe>()).sessionScoped(SharedFlowProbe::class) { mockk() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            graphs.sessionScoped(ForwarderProbe::class, SessionForwarding(unchecked = setOf("missing")), graphs::repository)
        }
    }

    private fun snapshot(todo: String, lifecycle: String) = SelfTodoSnapshot(
        todos = listOf(SubagentTodo(content = todo, status = "in_progress")),
        lifecycleStatus = lifecycle,
    )

    private class FakeSelfTodoRepository(val snapshot: SelfTodoSnapshot) : ISelfTodoRepository {
        private val state = MutableStateFlow(snapshot)

        override fun snapshotForFlow(conversationId: String) = state

        override fun snapshotFor(conversationId: String) = snapshot
    }

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
    }
}
