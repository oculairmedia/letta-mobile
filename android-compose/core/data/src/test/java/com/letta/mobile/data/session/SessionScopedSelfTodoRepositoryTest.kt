package com.letta.mobile.data.session

import com.letta.mobile.data.model.SelfTodoSnapshot
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedSelfTodoRepositoryTest {
    @Test
    fun `session switch emits one consistent snapshot`() = runTest {
        val old = FakeSelfTodoRepository(snapshot("old todo", "running"))
        val next = FakeSelfTodoRepository(snapshot("new todo", "completed"))
        val current = MutableStateFlow<ISelfTodoRepository>(old)
        val repository = SessionScopedSelfTodoRepository(current) { current.value }

        assertEquals(old.snapshot, repository.snapshotForFlow(CONVERSATION_ID).first())
        current.value = next
        assertEquals(next.snapshot, repository.snapshotForFlow(CONVERSATION_ID).first { it == next.snapshot })
        assertEquals(next.snapshot, repository.snapshotFor(CONVERSATION_ID))
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
