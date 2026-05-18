package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.TimelineSyncLoop
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimelineObserverTest {

    @Test
    fun `same conversation start is idempotent while observer is active`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1")

        harness.observer.start("conv-1")
        runCurrent()
        harness.observer.start("conv-1")
        runCurrent()

        coVerify(exactly = 1) { harness.timelineRepository.observe("conv-1") }
        coVerify(exactly = 1) { harness.timelineRepository.getOrCreate("conv-1") }
        assertEquals("conv-1", harness.currentConversationTracker.current)
    }

    @Test
    fun `switching conversations rebinds observer and tracker`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1")
        harness.seedTimeline("conv-2")

        harness.observer.start("conv-1")
        runCurrent()
        harness.observer.start("conv-2")
        runCurrent()

        coVerify(exactly = 1) { harness.timelineRepository.observe("conv-1") }
        coVerify(exactly = 1) { harness.timelineRepository.observe("conv-2") }
        assertEquals("conv-2", harness.currentConversationTracker.current)
    }

    @Test
    fun `older page prefix is prepended to subsequent live timeline emissions`() = runTest {
        val harness = Harness(backgroundScope)
        val liveFlow = harness.seedTimeline("conv-1", listOf(confirmed("live-1", "new")))
        harness.observer.start("conv-1")
        runCurrent()

        val older = uiMessage("older-1", "old")
        val merged = harness.observer.mergeOlderPage(
            conversationId = "conv-1",
            olderMessages = listOf(older),
            existingMessages = harness.uiState.value.messages,
        )
        assertEquals(listOf("older-1", "live-1"), merged.map { it.id })

        liveFlow.value = Timeline("conv-1", events = listOf(confirmed("live-1", "new"), confirmed("live-2", "newer")))
        runCurrent()

        assertEquals(listOf("older-1", "live-1", "live-2"), harness.uiState.value.messages.map { it.id })
    }

    @Test
    fun `active reply stream keeps streaming and typing flags true`() = runTest {
        val harness = Harness(backgroundScope, activeReplyConversationIds = setOf("conv-1"))
        harness.seedTimeline("conv-1")

        harness.observer.start("conv-1")
        runCurrent()

        assertTrue(harness.uiState.value.isStreaming)
        assertTrue(harness.uiState.value.isAgentTyping)
    }

    @Test
    fun `a2ui thinking stays active until first assistant response`() = runTest {
        var a2uiStartCount: Int? = 1
        var clearCount = 0
        val harness = Harness(
            scope = backgroundScope,
            a2uiThinkingStartMessageCount = { a2uiStartCount },
            clearA2uiThinkingOnResponse = {
                a2uiStartCount = null
                clearCount++
            },
        )
        val flow = harness.seedTimeline("conv-1", listOf(confirmed("user-1", "approved")))

        harness.observer.start("conv-1")
        runCurrent()

        assertTrue(harness.uiState.value.isStreaming)
        assertTrue(harness.uiState.value.isAgentTyping)
        assertEquals(0, clearCount)

        flow.value = Timeline(
            "conv-1",
            events = listOf(
                confirmed("user-1", "approved"),
                confirmed("assistant-2", "working", TimelineMessageType.REASONING),
            ),
        )
        runCurrent()

        assertEquals(1, clearCount)
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
    }

    @Test
    fun `confirmed assistant tail clears duplicate initial message in flight`() = runTest {
        var duplicateInFlight = true
        var clearCount = 0
        val harness = Harness(
            scope = backgroundScope,
            isFollowingDuplicateInitialMessageInFlight = { duplicateInFlight },
            clearFollowingDuplicateInitialMessageInFlight = {
                duplicateInFlight = false
                clearCount++
            },
        )
        harness.seedTimeline("conv-1", listOf(confirmed("assistant-1", "done", TimelineMessageType.ASSISTANT)))

        harness.observer.start("conv-1")
        runCurrent()

        assertFalse(duplicateInFlight)
        assertEquals(1, clearCount)
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
    }

    private class Harness(
        scope: CoroutineScope,
        activeReplyConversationIds: Set<String> = emptySet(),
        a2uiThinkingStartMessageCount: () -> Int? = { null },
        clearA2uiThinkingOnResponse: () -> Unit = {},
        isFollowingDuplicateInitialMessageInFlight: () -> Boolean = { false },
        clearFollowingDuplicateInitialMessageInFlight: () -> Unit = {},
    ) {
        val timelineRepository: TimelineRepository = mockk()
        val currentConversationTracker = CurrentConversationTracker()
        val activeReplyStreams = MutableStateFlow(activeReplyConversationIds)
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf()))
        val timelineFlows = mutableMapOf<String, MutableStateFlow<Timeline>>()
        private val syncEvents = MutableSharedFlow<TimelineSyncEvent>()
        private val loop: TimelineSyncLoop = mockk {
            every { events } returns syncEvents
        }
        val observer = ChatTimelineObserver(
            scope = scope,
            timelineRepository = timelineRepository,
            currentConversationTracker = currentConversationTracker,
            activeReplyStreams = activeReplyStreams,
            uiState = uiState,
            isClientModeStreamInFlight = { false },
            a2uiThinkingStartMessageCount = a2uiThinkingStartMessageCount,
            clearA2uiThinkingOnResponse = clearA2uiThinkingOnResponse,
            isFollowingDuplicateInitialMessageInFlight = isFollowingDuplicateInitialMessageInFlight,
            clearFollowingDuplicateInitialMessageInFlight = clearFollowingDuplicateInitialMessageInFlight,
            collapseCompletedRunsIfStreamingFinished = { _, next -> next },
        )

        init {
            coEvery { timelineRepository.observe(any()) } answers {
                timelineFlows.getValue(firstArg())
            }
            coEvery { timelineRepository.getOrCreate(any()) } returns loop
        }

        fun seedTimeline(
            conversationId: String,
            events: List<TimelineEvent> = emptyList(),
        ): MutableStateFlow<Timeline> {
            val flow = MutableStateFlow(Timeline(conversationId = conversationId, events = events))
            timelineFlows[conversationId] = flow
            return flow
        }
    }

    private fun uiMessage(id: String, content: String) = UiMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = Instant.parse("2026-05-10T00:00:00Z").toString(),
    )

    private fun confirmed(
        id: String,
        content: String,
        messageType: TimelineMessageType = TimelineMessageType.USER,
    ) = TimelineEvent.Confirmed(
        position = id.substringAfterLast('-').toDoubleOrNull() ?: 1.0,
        otid = "otid-$id",
        serverId = id,
        content = content,
        messageType = messageType,
        date = Instant.parse("2026-05-10T00:00:00Z"),
        runId = null,
        stepId = null,
        source = MessageSource.LETTA_SERVER,
    )

    @Suppress("unused")
    private fun localPending(id: String, content: String) = TimelineEvent.Local(
        position = 1.0,
        otid = id,
        content = content,
        role = Role.USER,
        sentAt = Instant.parse("2026-05-10T00:00:00Z"),
        deliveryState = DeliveryState.SENDING,
    )
}
