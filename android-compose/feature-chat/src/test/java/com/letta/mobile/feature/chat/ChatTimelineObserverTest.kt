package com.letta.mobile.feature.chat

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
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
import com.letta.mobile.util.Telemetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.feature.chat.coordination.ChatTimelineObserver
import com.letta.mobile.feature.chat.render.ChatMessageListChange
import com.letta.mobile.feature.chat.render.ChatUiState

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

    @Test
    fun `historical a2ui blocks fold into one surface snapshot without rendering raw blocks`() = runTest {
        val manager = A2uiSurfaceManager()
        val harness = Harness(
            scope = backgroundScope,
            syncA2uiHistorySnapshot = { _, messages ->
                manager.replaceWith(messages)
                manager.surfaces.value
            },
        )
        harness.seedTimeline(
            "conv-1",
            listOf(
                confirmed(
                    id = "assistant-1",
                    content = a2uiBlock(
                        """
                        [
                          {"version":"v0.9","createSurface":{"surfaceId":"old","catalogId":"basic"}},
                          {"version":"v0.9","updateComponents":{"surfaceId":"old","root":"oldText","components":[
                            {"id":"oldText","component":"Text","text":{"literalString":"Old"}}
                          ]}}
                        ]
                        """.trimIndent(),
                    ),
                    messageType = TimelineMessageType.ASSISTANT,
                ),
                confirmed(
                    id = "assistant-2",
                    content = a2uiBlock(
                        """
                        {"version":"v0.9","deleteSurface":{"surfaceId":"old"}}
                        """.trimIndent(),
                    ),
                    messageType = TimelineMessageType.ASSISTANT,
                ),
                confirmed(
                    id = "assistant-3",
                    content = a2uiBlock(
                        """
                        [
                          {"version":"v0.9","createSurface":{"surfaceId":"live","catalogId":"basic"}},
                          {"version":"v0.9","updateComponents":{"surfaceId":"live","root":"body","components":[
                            {"id":"body","component":"Text","text":{"path":"/message"}}
                          ]}},
                          {"version":"v0.9","updateDataModel":{"surfaceId":"live","path":"/message","value":"Final"}}
                        ]
                        """.trimIndent(),
                    ),
                    messageType = TimelineMessageType.ASSISTANT,
                ),
            ),
        )

        harness.observer.start("conv-1")
        runCurrent()

        assertEquals(emptyList<String>(), harness.uiState.value.messages.map { it.content })
        assertFalse(harness.uiState.value.a2uiSurfaces.containsKey("old"))
        val live = harness.uiState.value.a2uiSurfaces.getValue("live")
        assertEquals("body", live.rootComponentId)
        assertEquals(
            "Final",
            A2uiBindingResolver.resolvePath(live.dataModel, "/message")!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `unchanged timeline events reuse cached ui projection and report reuse counts`() = runTest {
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        val first = confirmed("assistant-1", "hello", TimelineMessageType.ASSISTANT)
        val flow = harness.seedTimeline("conv-1", listOf(first))

        harness.observer.start("conv-1")
        runCurrent()
        val firstProjectedMessage = harness.uiState.value.messages.single()

        flow.value = Timeline(
            "conv-1",
            events = listOf(
                first,
                confirmed("assistant-2", "next", TimelineMessageType.ASSISTANT),
            ),
        )
        runCurrent()

        assertSame(firstProjectedMessage, harness.uiState.value.messages.first())
        val projectionEvent = Telemetry.snapshot().first {
            it.tag == "TimelineSync" && it.name == "uiProjection.snapshot" && it.attrs["eventsTotal"] == 2
        }
        assertEquals(1, projectionEvent.attrs["eventsReused"])
        assertEquals(1, projectionEvent.attrs["eventsProjected"])
        assertEquals(2, projectionEvent.attrs["messageCount"])
    }

    @Test
    fun `tail-only append reuses prior projected message instances`() = runTest {
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        val history = (1..200).map { index ->
            confirmed("user-$index", "prompt $index")
        }
        val flow = harness.seedTimeline("conv-1", history)

        harness.observer.start("conv-1")
        runCurrent()
        val projectedPrefix = harness.uiState.value.messages.take(199)

        flow.value = flow.value.append(confirmed("assistant-201", "streaming", TimelineMessageType.ASSISTANT))
        runCurrent()

        assertEquals(projectedPrefix.map { it.id }, harness.uiState.value.messages.take(199).map { it.id })
        projectedPrefix.forEachIndexed { index, message ->
            assertSame(message, harness.uiState.value.messages[index])
        }
        assertEquals("assistant-201", harness.uiState.value.messages.last().id)
        assertEquals(ChatMessageListChange.AppendTail, harness.uiState.value.messageListChange)
    }

    @Test
    fun `streaming tail replacement reuses unchanged history without full projection`() = runTest {
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        var timeline = Timeline("conv-1")
        repeat(64) { index ->
            timeline = timeline.append(confirmed("user-$index", "history-$index"))
        }
        timeline = timeline.append(confirmed("assistant-tail", "hel", TimelineMessageType.ASSISTANT))
        val flow = harness.seedTimeline(timeline)

        harness.observer.start("conv-1")
        runCurrent()
        val projectedHistory = harness.uiState.value.messages.dropLast(1)
        Telemetry.clear()

        flow.value = timeline.replaceByServerId(
            confirmed("assistant-tail", "hello", TimelineMessageType.ASSISTANT),
        )
        runCurrent()

        assertEquals("hello", harness.uiState.value.messages.last().content)
        assertEquals(ChatMessageListChange.ReplaceTail, harness.uiState.value.messageListChange)
        projectedHistory.forEachIndexed { index, message ->
            assertSame(message, harness.uiState.value.messages[index])
        }
        val projectionEvent = Telemetry.snapshot().first {
            it.tag == "TimelineSync" && it.name == "uiProjection.snapshot"
        }
        assertEquals(true, projectionEvent.attrs["fastPath"])
        assertEquals(64, projectionEvent.attrs["eventsReused"])
        assertEquals(1, projectionEvent.attrs["eventsProjected"])
    }

    @Test
    fun `changed timeline event with same identity invalidates cached ui projection`() = runTest {
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        val flow = harness.seedTimeline(
            "conv-1",
            listOf(confirmed("assistant-1", "first", TimelineMessageType.ASSISTANT)),
        )

        harness.observer.start("conv-1")
        runCurrent()
        val firstProjectedMessage = harness.uiState.value.messages.single()

        flow.value = Timeline(
            "conv-1",
            events = listOf(confirmed("assistant-1", "edited", TimelineMessageType.ASSISTANT)),
        )
        runCurrent()

        val updatedMessage = harness.uiState.value.messages.single()
        assertEquals("edited", updatedMessage.content)
        assertNotSame(firstProjectedMessage, updatedMessage)
        val projectionEvent = Telemetry.snapshot().first {
            it.tag == "TimelineSync" && it.name == "uiProjection.snapshot" && it.attrs["eventsTotal"] == 1
        }
        assertEquals(0, projectionEvent.attrs["eventsReused"])
        assertEquals(1, projectionEvent.attrs["eventsProjected"])
    }

    @Test
    fun `unchanged streaming tick is deduped and does not re-project or rewrite uiState`() = runTest {
        // letta-mobile-yflpp: a streaming tick that re-emits the SAME tail event
        // (no real content change) must NOT run a new projection or rewrite
        // uiState — that no-op churn was pegging the UI thread (~20 projections
        // /sec over 85+ tool cards) and dropping tool-card taps mid-stream.
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        var timeline = Timeline("conv-1")
        repeat(64) { index ->
            timeline = timeline.append(confirmed("user-$index", "history-$index"))
        }
        timeline = timeline.append(confirmed("assistant-tail", "hello", TimelineMessageType.ASSISTANT))
        val flow = harness.seedTimeline(timeline)

        harness.observer.start("conv-1")
        runCurrent()
        val stateAfterFirst = harness.uiState.value
        val messagesAfterFirst = harness.uiState.value.messages
        Telemetry.clear()

        // Re-emit a DISTINCT Timeline instance that renders identically — only a
        // non-rendered field (liveCursor) changed. This is exactly the storm
        // signature: the reducer's `copy(liveCursor = serverId)` after a STALE/
        // EQUAL merge makes the Timeline `!=` (so the StateFlow emits) while the
        // visible tail is unchanged. The dedupe must treat this as a no-op.
        flow.value = timeline.copy(liveCursor = "live-cursor-bump")
        runCurrent()

        // uiState must be the SAME instance (no rewrite => no recomposition).
        assertSame(stateAfterFirst, harness.uiState.value)
        assertSame(messagesAfterFirst, harness.uiState.value.messages)
        // No new full projection snapshot for the no-op tick.
        val snapshots = Telemetry.snapshot().filter {
            it.tag == "TimelineSync" && it.name == "uiProjection.snapshot"
        }
        assertTrue("expected no uiProjection.snapshot for a no-op tick", snapshots.isEmpty())
        // A suppressed counter is surfaced instead so the dedupe is observable.
        val suppressed = Telemetry.snapshot().filter {
            it.tag == "TimelineSync" && it.name == "uiProjection.suppressed"
        }
        assertTrue("expected a uiProjection.suppressed event", suppressed.isNotEmpty())
    }

    @Test
    fun `a real tail change after a deduped no-op still projects`() = runTest {
        // Guard: dedupe must not stick. After a no-op tick, a genuine content
        // change must still produce a fresh projection.
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        var timeline = Timeline("conv-1")
        repeat(8) { index ->
            timeline = timeline.append(confirmed("user-$index", "history-$index"))
        }
        timeline = timeline.append(confirmed("assistant-tail", "hel", TimelineMessageType.ASSISTANT))
        val flow = harness.seedTimeline(timeline)

        harness.observer.start("conv-1")
        runCurrent()

        // No-op tick (only a non-rendered field changed).
        flow.value = timeline.copy(liveCursor = "bump-1")
        runCurrent()
        // Real change.
        flow.value = timeline.replaceByServerId(
            confirmed("assistant-tail", "hello", TimelineMessageType.ASSISTANT),
        )
        runCurrent()

        assertEquals("hello", harness.uiState.value.messages.last().content)
        assertEquals(ChatMessageListChange.ReplaceTail, harness.uiState.value.messageListChange)
    }

    @Test
    fun `rapid burst of distinct ticks coalesces while a projection is in flight`() = runTest {
        // letta-mobile-yflpp COALESCE: when many distinct timeline emissions
        // arrive faster than they can be projected, conflate() must collapse
        // them — only the LATEST is projected, never the whole backlog.
        val harness = Harness(backgroundScope)
        var timeline = Timeline("conv-1")
        repeat(8) { index ->
            timeline = timeline.append(confirmed("user-$index", "history-$index"))
        }
        timeline = timeline.append(confirmed("assistant-tail", "t0", TimelineMessageType.ASSISTANT))
        val flow = harness.seedTimeline(timeline)

        harness.observer.start("conv-1")
        runCurrent()
        Telemetry.clear()

        // Push 20 distinct tail values without yielding to the collector.
        repeat(20) { i ->
            timeline = timeline.replaceByServerId(
                confirmed("assistant-tail", "token-$i", TimelineMessageType.ASSISTANT),
            )
            flow.value = timeline
        }
        runCurrent()

        // The latest value wins; the collector did not process all 20.
        assertEquals("token-19", harness.uiState.value.messages.last().content)
        val snapshots = Telemetry.snapshot().count {
            it.tag == "TimelineSync" && it.name == "uiProjection.snapshot"
        }
        assertTrue(
            "expected coalesced projections (<20) but ran $snapshots",
            snapshots < 20,
        )
    }

    @Test
    fun `long history streaming tail projection does not scan full history per frame`() = runTest {
        Telemetry.clear()
        val harness = Harness(backgroundScope)
        val tailId = "assistant-513"
        var timeline = Timeline("conv-1")
        repeat(512) { index ->
            timeline = timeline.append(confirmed("user-${index + 1}", "history ${index + 1}"))
        }
        timeline = timeline.append(confirmed(tailId, "token 0", TimelineMessageType.ASSISTANT))
        val flow = harness.seedTimeline(timeline)

        harness.observer.start("conv-1")
        runCurrent()

        repeat(16) { frame ->
            timeline = timeline.replaceByServerId(
                confirmed(tailId, "token ${frame + 1}", TimelineMessageType.ASSISTANT),
            )
            flow.value = timeline
            runCurrent()
        }

        val fastPathEvent = Telemetry.snapshot().last {
            it.tag == "TimelineSync" &&
                it.name == "uiProjection.snapshot" &&
                it.attrs["eventsTotal"] == 513 &&
                it.attrs["fastPath"] == true
        }
        assertEquals(1, fastPathEvent.attrs["eventsProjected"])
        assertEquals(0, fastPathEvent.attrs["prefixEventsChecked"])
        assertEquals(513, harness.uiState.value.messages.size)
        assertEquals("token 16", harness.uiState.value.messages.last().content)
        assertEquals(ChatMessageListChange.ReplaceTail, harness.uiState.value.messageListChange)
    }

    private class Harness(
        scope: CoroutineScope,
        activeReplyConversationIds: Set<String> = emptySet(),
        a2uiThinkingStartMessageCount: () -> Int? = { null },
        clearA2uiThinkingOnResponse: () -> Unit = {},
        isFollowingDuplicateInitialMessageInFlight: () -> Boolean = { false },
        clearFollowingDuplicateInitialMessageInFlight: () -> Unit = {},
        syncA2uiHistorySnapshot: (String, List<A2uiMessage>) -> Map<String, A2uiSurfaceState> =
            { _, _ -> emptyMap() },
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
            syncA2uiHistorySnapshot = syncA2uiHistorySnapshot,
            projectionDispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.Default,
            // Disable frame pacing so virtual-clock emissions stay synchronous
            // under runCurrent(); coalescing is exercised separately.
            projectionFrameIntervalMs = 0L,
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
        ): MutableStateFlow<Timeline> = seedTimeline(Timeline(conversationId = conversationId, events = events))

        fun seedTimeline(timeline: Timeline): MutableStateFlow<Timeline> {
            val flow = MutableStateFlow(timeline)
            timelineFlows[timeline.conversationId] = flow
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

    private fun a2uiBlock(payload: String): String =
        """
        <a2ui-json>
        $payload
        </a2ui-json>
        """.trimIndent()

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
