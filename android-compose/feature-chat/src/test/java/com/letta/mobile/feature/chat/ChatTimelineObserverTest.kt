package com.letta.mobile.feature.chat
import com.letta.mobile.ui.chat.render.*

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
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import com.letta.mobile.feature.chat.coordination.ChatRunExpansionState
import com.letta.mobile.feature.chat.coordination.ChatTimelineObserver
import com.letta.mobile.data.chat.projection.ChatMessageListChange

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

        // ChatTimelineObserver.start(conversationId) delegates to the
        // agentId-scoped start(agentId = null, conversationId), which calls
        // the two-arg observe/getOrCreate overloads.
        coVerify(exactly = 1) { harness.timelineRepository.observe(null, "conv-1") }
        coVerify(exactly = 1) { harness.timelineRepository.getOrCreate(null, "conv-1") }
        assertEquals("conv-1", harness.currentConversationTracker.current)
    }

    @Test
    fun `warm switch publishes cached target rows without empty loading frame`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1", listOf(confirmed("assistant-1", "from-1", TimelineMessageType.ASSISTANT)))
        harness.seedTimeline("conv-2", listOf(confirmed("assistant-2", "from-2", TimelineMessageType.ASSISTANT)))

        harness.observer.start("conv-1")
        runCurrent()
        val publications = mutableListOf<ChatUiState>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.uiState.collect { publications += it }
        }
        runCurrent()
        publications.clear()

        harness.observer.start("conv-2")

        assertEquals(listOf("assistant-2"), harness.uiState.value.messages.map { it.id })
        assertFalse(harness.uiState.value.isLoadingMessages)
        assertTrue(publications.none { it.messages.isEmpty() && it.isLoadingMessages })
        assertTrue(publications.none { state -> state.messages.any { it.id == "assistant-1" } })
        collection.cancel()
    }

    @Test
    fun `warm switch with shared conversation id projects only target agent cache`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("agent-a", "default", listOf(confirmed("assistant-a", "from a")))
        harness.seedTimeline("agent-b", "default", listOf(confirmed("assistant-b", "from b")))

        harness.observer.start("agent-a", "default")
        runCurrent()
        harness.observer.start("agent-b", "default")

        assertEquals(listOf("assistant-b"), harness.uiState.value.messages.map { it.id })
        assertFalse(harness.uiState.value.isLoadingMessages)
    }

    @Test
    fun `cached empty target is immediately ready empty`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1", listOf(confirmed("assistant-1", "from-1")))
        harness.seedTimeline("conv-2")
        harness.observer.start("conv-1")
        runCurrent()

        harness.observer.start("conv-2")

        assertTrue(harness.uiState.value.messages.isEmpty())
        assertFalse(harness.uiState.value.isLoadingMessages)
    }

    @Test
    fun `true cache miss target may load`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1", listOf(confirmed("assistant-1", "from-1")))
        harness.observer.start("conv-1")
        runCurrent()

        harness.observer.start("conv-miss")

        assertTrue(harness.uiState.value.messages.isEmpty())
        assertTrue(harness.uiState.value.isLoadingMessages)
    }

    @Test
    fun `hydrated with pending projection keeps loading until messages arrive`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline("conv-1")

        harness.observer.start("conv-1")
        runCurrent()

        harness.emitSyncEvent(TimelineSyncEvent.Hydrated(messageCount = 3))
        runCurrent()
        assertTrue(harness.uiState.value.isLoadingMessages)

        harness.emitSyncEvent(TimelineSyncEvent.Hydrated(messageCount = 0))
        runCurrent()
        assertFalse(harness.uiState.value.isLoadingMessages)
    }

    @Test
    fun `recoverable hydration failure keeps fallback rows without init error`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline(
            "conv-fallback",
            listOf(confirmed("assistant-fallback", "last known good", TimelineMessageType.ASSISTANT)),
        )

        harness.observer.start("conv-fallback")
        runCurrent()
        harness.emitSyncEvent(TimelineSyncEvent.HydrateFailed("active snapshot corrupt; remote offline"))
        runCurrent()

        assertEquals(listOf("assistant-fallback"), harness.uiState.value.messages.map { it.id })
        assertFalse(harness.uiState.value.isLoadingMessages)
        assertTrue(harness.uiState.value.error?.contains("Timeline init failed") != true)
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

        coVerify(exactly = 1) { harness.timelineRepository.observe(null, "conv-1") }
        coVerify(exactly = 1) { harness.timelineRepository.observe(null, "conv-2") }
        assertEquals("conv-2", harness.currentConversationTracker.current)
    }


    @Test
    fun `scoped observer projects new material for bound agent conversation`() = runTest {
        val harness = Harness(backgroundScope)
        val flow = harness.seedTimeline(agentId = "agent-a", conversationId = "default")

        harness.observer.start(agentId = "agent-a", conversationId = "default")
        runCurrent()

        flow.value = Timeline(
            "default",
            events = persistentListOf(confirmed("assistant-1", "done", TimelineMessageType.ASSISTANT)),
        )
        runCurrent()

        assertEquals(listOf("assistant-1"), harness.uiState.value.messages.map { it.id })
        assertEquals("done", harness.uiState.value.messages.single().content)
        coVerify(exactly = 1) { harness.timelineRepository.observe("agent-a", "default") }
        coVerify(exactly = 1) { harness.timelineRepository.getOrCreate("agent-a", "default") }
    }

    @Test
    fun `same default conversation id is isolated by agent scope`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline(
            agentId = "agent-a",
            conversationId = "default",
            events = listOf(confirmed("assistant-a", "agent a", TimelineMessageType.ASSISTANT)),
        )
        val agentBFlow = harness.seedTimeline(agentId = "agent-b", conversationId = "default")

        harness.observer.start(agentId = "agent-a", conversationId = "default")
        runCurrent()
        assertEquals(listOf("assistant-a"), harness.uiState.value.messages.map { it.id })

        agentBFlow.value = Timeline(
            "default",
            events = persistentListOf(confirmed("assistant-b", "agent b", TimelineMessageType.ASSISTANT)),
        )
        runCurrent()

        assertEquals(listOf("assistant-a"), harness.uiState.value.messages.map { it.id })
        coVerify(exactly = 0) { harness.timelineRepository.observe("agent-b", "default") }
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

        liveFlow.value = Timeline(
            "conv-1",
            events = persistentListOf(confirmed("live-1", "new"), confirmed("live-2", "newer")),
        )
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

        // A genuine assistant reply (not a reasoning frame — see the sibling
        // "stays active during streamed reasoning frames" test, which proves
        // reasoning-typed messages must NOT clear it) is what actually
        // clears the a2ui thinking gate.
        flow.value = Timeline(
            "conv-1",
            events = persistentListOf(
                confirmed("user-1", "approved"),
                confirmed("assistant-2", "working", TimelineMessageType.ASSISTANT),
            ),
        )
        runCurrent()

        assertEquals(1, clearCount)
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
    }

    @Test
    fun `a2ui thinking stays active during streamed reasoning frames`() = runTest {
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

        // Add a reasoning frame; a2ui thinking shouldn't clear since it's not an assistant response
        flow.value = Timeline(
            "conv-1",
            events = persistentListOf(
                confirmed("user-1", "approved"),
                confirmed("reasoning-2", "reasoning...", TimelineMessageType.REASONING)
            ),
        )
        runCurrent()

        assertEquals(0, clearCount)
        assertTrue(harness.uiState.value.isStreaming)
        assertTrue(harness.uiState.value.isAgentTyping)
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
            events = persistentListOf(
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
            events = persistentListOf(confirmed("assistant-1", "edited", TimelineMessageType.ASSISTANT)),
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
        // uiState â€” that no-op churn was pegging the UI thread (~20 projections
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
        flow.value = timeline.copy(liveCursor = "prime")
        runCurrent()
        val stateAfterFirst = harness.uiState.value
        val messagesAfterFirst = harness.uiState.value.messages
        Telemetry.clear()

        // Re-emit a DISTINCT Timeline instance that renders identically â€” only a
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
        // them â€” only the LATEST is projected, never the whole backlog.
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

    @Test
    fun `empty timeline after reconnect-clear does not crash on projection tick (letta-mobile-ixtzn)`() = runTest {
        val harness = Harness(backgroundScope)
        val flow = harness.seedTimeline("conv-1", listOf(confirmed("assistant-1", "initial", TimelineMessageType.ASSISTANT)))
        
        harness.observer.start("conv-1")
        runCurrent()
        
        assertEquals(1, harness.uiState.value.messages.size)
        
        // Simulate reconnect-clear: empty timeline emitted before new frames arrive
        flow.value = Timeline("conv-1")
        runCurrent()
        
        // Should not crash; empty projection accepted gracefully
        assertEquals(0, harness.uiState.value.messages.size)
        
        // New frame arrives on cleared timeline
        flow.value = Timeline("conv-1", events = persistentListOf(confirmed("assistant-2", "after reconnect", TimelineMessageType.ASSISTANT)))
        runCurrent()
        
        assertEquals(1, harness.uiState.value.messages.size)
        assertEquals("assistant-2", harness.uiState.value.messages.first().id)
    }

    @Test
    fun `out-of-band frame arriving before first message does not crash (letta-mobile-ixtzn)`() = runTest {
        val harness = Harness(backgroundScope)
        // Start with an empty timeline (fresh conversation)
        val flow = harness.seedTimeline("conv-1")
        
        harness.observer.start("conv-1")
        runCurrent()
        
        assertEquals(0, harness.uiState.value.messages.size)
        
        // Out-of-band frame arrives on fresh/empty timeline
        // The guard at line 383 ensures we don't crash when previous.records is empty
        flow.value = Timeline("conv-1", events = persistentListOf(confirmed("frame-1", "hello", TimelineMessageType.ASSISTANT)))
        runCurrent()
        
        // Should not crash; projection succeeds
        assertEquals(1, harness.uiState.value.messages.size)
        assertEquals("frame-1", harness.uiState.value.messages.first().id)
    }

    // region letta-mobile-ah1ng: terminal-run collapse reconciliation through
    // the REAL observer→ChatRunExpansionState production path (the harness no
    // longer injects a no-op collapse callback).

    @Test
    fun `completed run first seen via hydration defaults collapsed`() = runTest {
        val harness = Harness(backgroundScope)
        harness.seedTimeline(
            "conv-1",
            listOf(
                confirmed("h-10", "hi"),
                confirmed("h-20", "settled long ago", TimelineMessageType.ASSISTANT, runId = "run-hist"),
            ),
        )

        harness.observer.start("conv-1")
        runCurrent()

        assertEquals(listOf("h-10", "h-20"), harness.uiState.value.messages.map { it.id })
        // No isStreaming edge ever fired here; per-run terminal reconciliation
        // must still fold the completed run.
        assertTrue(harness.uiState.value.collapsedRunIds.contains("run-hist"))
    }

    @Test
    fun `live terminal transition collapses run once presence clears`() = runTest {
        val harness = Harness(backgroundScope, activeReplyConversationIds = setOf("conv-1"))
        val flow = harness.seedTimeline(
            "conv-1",
            listOf(
                confirmed("t-10", "go"),
                confirmed("t-20", "partial answer", TimelineMessageType.ASSISTANT, runId = "run-live"),
            ),
        )

        harness.observer.start("conv-1")
        runCurrent()

        assertTrue(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.collapsedRunIds.contains("run-live"))

        // Presence clears via a presence-only (deduped projection) tick — the
        // publication must still route through terminal reconciliation.
        harness.activeReplyStreams.value = emptySet()
        flow.value = flow.value.copy(liveCursor = "presence-bump")
        runCurrent()

        assertFalse(harness.uiState.value.isStreaming)
        assertTrue(harness.uiState.value.collapsedRunIds.contains("run-live"))
    }

    @Test
    fun `reconcile error presence clear collapses the terminal run`() = runTest {
        val harness = Harness(backgroundScope, activeReplyConversationIds = setOf("conv-1"))
        harness.seedTimeline(
            "conv-1",
            listOf(
                confirmed("e-10", "go"),
                confirmed("e-20", "partial answer", TimelineMessageType.ASSISTANT, runId = "run-error"),
            ),
        )

        harness.observer.start("conv-1")
        runCurrent()
        assertTrue(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.collapsedRunIds.contains("run-error"))

        harness.emitSyncEvent(TimelineSyncEvent.ReconcileError("sync failed"))
        runCurrent()

        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertEquals("Couldn't sync agent reply — pull to refresh", harness.uiState.value.error)
        assertTrue(harness.uiState.value.collapsedRunIds.contains("run-error"))
    }

    @Test
    fun `terminal run collapses even when a newer turn starts before presence clears`() = runTest {
        // Ordering regression: run-1's terminal projection landed while the
        // streaming edge was consumed by a later turn. The old newest-run-only,
        // edge-gated selection left run-1 expanded forever.
        val harness = Harness(backgroundScope, activeReplyConversationIds = setOf("conv-1"))
        val flow = harness.seedTimeline(
            "conv-1",
            listOf(
                confirmed("d-10", "first question"),
                confirmed("d-20", "answer one", TimelineMessageType.ASSISTANT, runId = "run-1"),
            ),
        )

        harness.observer.start("conv-1")
        runCurrent()

        assertTrue(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.collapsedRunIds.contains("run-1"))

        // A second turn starts before presence ever drops.
        flow.value = Timeline(
            "conv-1",
            events = persistentListOf(
                confirmed("d-10", "first question"),
                confirmed("d-20", "answer one", TimelineMessageType.ASSISTANT, runId = "run-1"),
                confirmed("d-30", "second question"),
                confirmed("d-40", "working", TimelineMessageType.ASSISTANT, runId = "run-2"),
            ),
        )
        runCurrent()

        assertTrue(harness.uiState.value.collapsedRunIds.contains("run-1"))
        assertFalse("active newest run stays open", harness.uiState.value.collapsedRunIds.contains("run-2"))

        // Presence finally clears; run-2 settles as well and prior runs stay folded.
        harness.activeReplyStreams.value = emptySet()
        flow.value = flow.value.copy(liveCursor = "settle-bump")
        runCurrent()

        assertTrue(harness.uiState.value.collapsedRunIds.containsAll(setOf("run-1", "run-2")))
    }

    // endregion

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
        // letta-mobile-ah1ng: the harness routes publications through a REAL
        // ChatRunExpansionState exactly like AdminChatViewModel does. The old
        // `{ _, next -> next }` stub made every collapse regression invisible
        // at this layer.
        val savedStateHandle = SavedStateHandle()
        private val runExpansionState = ChatRunExpansionState(savedStateHandle, uiState)
        val timelineFlows = mutableMapOf<TimelineHarnessKey, MutableStateFlow<Timeline>>()
        private val syncEvents = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 16)
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
            reconcileCollapsedRunsOnProjection =
                { previous, next -> runExpansionState.reconcileCollapsedRunsOnProjection(previous, next) },
            syncA2uiHistorySnapshot = syncA2uiHistorySnapshot,
            projectionDispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.Default,
            // Disable frame pacing so virtual-clock emissions stay synchronous
            // under runCurrent(); coalescing is exercised separately.
            projectionFrameIntervalMs = 0L,
        )

        init {
            coEvery { timelineRepository.observe(any<String>()) } answers {
                timelineFlows.getValue(TimelineHarnessKey(null, firstArg()))
            }
            coEvery { timelineRepository.observe(any<String>(), any()) } answers {
                timelineFlows.getValue(TimelineHarnessKey(firstArg(), secondArg()))
            }
            coEvery { timelineRepository.getOrCreate(any<String>()) } returns loop
            coEvery { timelineRepository.getOrCreate(any<String>(), any()) } returns loop
            every { timelineRepository.peekCached(any(), any()) } answers {
                timelineFlows[TimelineHarnessKey(firstArg(), secondArg())]?.value
            }
        }

        fun seedTimeline(
            conversationId: String,
            events: List<TimelineEvent> = emptyList(),
        ): MutableStateFlow<Timeline> = seedTimeline(agentId = null, conversationId = conversationId, events = events)

        fun seedTimeline(
            agentId: String?,
            conversationId: String,
            events: List<TimelineEvent> = emptyList(),
        ): MutableStateFlow<Timeline> = seedTimeline(
            agentId = agentId,
            timeline = Timeline(conversationId = conversationId, events = events.toPersistentList()),
        )

        fun seedTimeline(timeline: Timeline): MutableStateFlow<Timeline> = seedTimeline(agentId = null, timeline = timeline)

        fun seedTimeline(agentId: String?, timeline: Timeline): MutableStateFlow<Timeline> {
            val flow = MutableStateFlow(timeline)
            timelineFlows[TimelineHarnessKey(agentId, timeline.conversationId)] = flow
            return flow
        }

        suspend fun emitSyncEvent(event: TimelineSyncEvent) {
            syncEvents.emit(event)
        }
    }

    private data class TimelineHarnessKey(
        val agentId: String?,
        val conversationId: String,
    )

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
        runId: String? = null,
    ) = TimelineEvent.Confirmed(
        position = id.substringAfterLast('-').toDoubleOrNull() ?: 1.0,
        otid = "otid-$id",
        serverId = id,
        content = content,
        messageType = messageType,
        date = Instant.parse("2026-05-10T00:00:00Z"),
        runId = runId,
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
