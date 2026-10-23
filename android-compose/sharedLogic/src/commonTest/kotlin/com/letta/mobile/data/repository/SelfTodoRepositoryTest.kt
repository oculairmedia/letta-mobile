package com.letta.mobile.data.repository

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SelfTodoRepositoryTest {
    @Test
    fun foregroundUsesSameReplacementParserForSingularAndArrayCallsWithoutCrossingConversations() = runTest {
        val frames = MutableSharedFlow<ServerFrame>()
        val transport = object : NoOpChannelTransport() { override val events = frames }
        val repository = SelfTodoRepository(transport, backgroundScope)
        runCurrent()
        frames.emit(ServerFrame.ToolCallMessage(
            id = "message-1", ts = "t", agentId = "parent", conversationId = "conversation-1",
            toolCall = ToolCallPayload(
                "todo-1", "TodoWrite", """{"todos":[{"content":"Read","status":"completed","activeForm":"Reading"}]}""",
            ),
        ))
        assertEquals("Read", repository.snapshotFor("conversation-1").todos.single().content)
        frames.emit(ServerFrame.ToolCallMessage(
            id = "message-2", ts = "t", agentId = "parent", conversationId = "conversation-2",
            toolCalls = listOf(
                ToolCallPayload("todo-2", "TodoWrite", """{"todos":[]}"""),
                ToolCallPayload("plan-1", "update_plan", """{"plan":[{"step":"Verify","status":"pending"}]}"""),
            ),
        ))
        assertEquals("Read", repository.snapshotFor("conversation-1").todos.single().content)
        assertEquals("Verify", repository.snapshotFor("conversation-2").todos.single().content)
    }

    @Test
    fun malformedReplacementPreservesPlanAndExplicitEmptyReplacementClearsIt() = runTest {
        val frames = MutableSharedFlow<ServerFrame>()
        val transport = object : NoOpChannelTransport() { override val events = frames }
        val repository = SelfTodoRepository(transport, backgroundScope)
        runCurrent()
        val frame = ServerFrame.ToolCallMessage(
            id = "message", ts = "t", agentId = "parent", conversationId = "conversation",
            toolCall = ToolCallPayload(
                "todo", "TodoWrite", """{"todos":[{"content":"Test","status":"pending","activeForm":"Testing"}]}""",
            ),
        )
        val toolCall = requireNotNull(frame.toolCall)
        frames.emit(frame)
        frames.emit(frame.copy(toolCall = toolCall.copy(arguments = """{"todos":[null]}""")))
        assertEquals(1, repository.snapshotFor("conversation").todos.size)
        frames.emit(frame.copy(toolCall = toolCall.copy(arguments = """{"todos":[]}""")))
        assertEquals(emptyList(), repository.snapshotFor("conversation").todos)
    }
}
