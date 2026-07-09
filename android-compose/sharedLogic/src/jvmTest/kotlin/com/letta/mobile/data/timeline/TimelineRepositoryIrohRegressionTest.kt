package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRepositoryIrohRegressionTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.timelineSyncGateDebugEnabled.set(false)
    }

    @Test
    fun `observe null conversation and scoped ingest share one StateFlow after split loops existed`() = runTest {
        val repository = TimelineRepository(NoopTimelineTransport(), NoOpPendingLocalStore, NoOpConversationCursorStore)
        val unscoped = repository.observe(agentId = null, conversationId = "conv-alias")
        val scopedLoopBeforeAlias = repository.getOrCreate(agentId = "agent-1", conversationId = "conv-alias")
        advanceUntilIdle()

        assertSame(unscoped, scopedLoopBeforeAlias.state)

        repository.ingestExternalTransportMessage(
            agentId = "agent-1",
            conversationId = "conv-alias",
            message = AssistantMessage(
                id = "assistant-final",
                contentRaw = JsonPrimitive("visible now"),
                otid = "assistant-otid",
            ),
        )
        advanceUntilIdle()

        assertSame(unscoped, repository.observe(agentId = null, conversationId = "conv-alias"))
        assertSame(unscoped, repository.observe(agentId = "agent-1", conversationId = "conv-alias"))
        assertEquals(1, unscoped.value.events.size)
        assertEquals("visible now", unscoped.value.events.single().content)
        assertEquals(1, repository.cachedLoopCount())
    }

    @Test
    fun `tool turn iroh frames emit visible timeline for tool return and final without reconcile`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loop = TimelineSyncLoop(
            messageApi = NoopTimelineTransport(),
            conversationId = "conv-tool-turn",
            scope = backgroundScope,
        )
        val emissions = mutableListOf<Timeline>()
        val collector = launch(dispatcher) {
            loop.state.collect { emissions += it }
        }
        runCurrent()

        loop.ingestStreamEvent(AssistantMessage(
            id = "assistant-preamble-1",
            contentRaw = JsonPrimitive("I'll check."),
            runId = "run-tool",
            otid = "assistant-pre",
            seqId = 1,
        ))
        runCurrent()
        loop.ingestStreamEvent(ToolCallMessage(
            id = "tool-call-1",
            toolCall = ToolCall(toolCallId = "call-1", name = "read", arguments = "{}"),
            runId = "run-tool",
            seqId = 2,
        ))
        runCurrent()
        loop.ingestStreamEvent(ToolReturnMessage(
            id = "tool-return-1",
            toolCallId = "call-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("file contents"),
            seqId = 3,
        ))
        runCurrent()
        loop.ingestStreamEvent(AssistantMessage(
            id = "assistant-final-1",
            contentRaw = JsonPrimitive("The file says hello."),
            runId = "run-tool",
            otid = "assistant-final-otid",
            seqId = 4,
        ))
        runCurrent()
        loop.ingestStreamEvent(com.letta.mobile.data.model.UnknownMessage(
            id = "usage-1",
            messageType = "usage_statistics",
            stepId = "step-usage",
        ))
        runCurrent()

        val eventCounts = emissions.map { it.events.size }
        assertTrue(eventCounts.containsAll(listOf(0, 1, 2, 3)))
        val toolReturnEmission = emissions.firstOrNull { timeline ->
            val tool = timeline.events.getOrNull(1) as? TimelineEvent.Confirmed
            tool?.toolReturnContentByCallId?.get("call-1") == "file contents"
        }
        assertTrue(toolReturnEmission != null, "tool return should emit without reconcile")
        val finalEmission = emissions.firstOrNull { timeline ->
            timeline.events.any { it.content == "The file says hello." }
        }
        assertTrue(finalEmission != null, "post-tool assistant final should emit without reconcile")
        assertEquals("The file says hello.", loop.state.value.events.last().content)

        collector.cancel()
        loop.close()
    }

    private class NoopTimelineTransport : TimelineTransport {
        private val stream = MutableSharedFlow<TimelineStreamFrame>()

        override suspend fun sendConversationMessage(
            conversationId: String,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = emptyFlow()

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = stream

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> = emptyList()

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }
}
