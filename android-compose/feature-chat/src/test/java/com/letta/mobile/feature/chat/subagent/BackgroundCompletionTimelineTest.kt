package com.letta.mobile.feature.chat.subagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.PagedChatMessageList
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundCompletionTimelineTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun initialHistoryLoadDoesNotPresentOnlyTheOptimisticMessage() = exerciseInitialLoad(true)

    @Test
    fun emptyHistoryStillReleasesTheInitialLoadingGate() = exerciseInitialLoad(false)

    @Test
    fun initialHistoryFailureOffersRetryWithoutShowingOnlyPendingContent() = exerciseInitialLoad(true, true)

    @Test
    fun returningToResidentPresentationDoesNotFlashLoadingOrReloadHistory() = exerciseInitialLoad(true, warmReentry = true)

    private fun exerciseInitialLoad(hasHistory: Boolean, failFirst: Boolean = false, warmReentry: Boolean = false) {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempts = 0
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val cacheJob = kotlinx.coroutines.SupervisorJob()
        val cacheScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + cacheJob)
        fun row(id: String, body: String): ChatRenderItem = ChatRenderItem.Single(
            UiMessage(id = id, role = "user", content = body, timestamp = "2026-09-13T00:00:00Z"),
            GroupPosition.None,
        )
        val history = if (hasHistory) listOf(row("older", "Existing conversation history")) else emptyList()
        val pager = androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 20)) {
            object : androidx.paging.PagingSource<Int, ChatRenderItem>() {
                override fun getRefreshKey(state: androidx.paging.PagingState<Int, ChatRenderItem>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChatRenderItem> {
                    loads.incrementAndGet()
                    started.complete(Unit)
                    ready.await()
                    if (failFirst && attempts++ == 0) return LoadResult.Error(IllegalStateException("Initial history unavailable"))
                    return LoadResult.Page(history, prevKey = null, nextKey = null)
                }
            }
        }
        val presentation = ChatPagingPresentation(pager.flow.cachedIn(cacheScope),
            MutableStateFlow(listOf(row("latest", "Latest optimistic message"))), {})
        val callbacks = ChatContentCallbacks(
            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
        )
        compose.setContent {
            LettaChatTheme {
                Box(Modifier.size(320.dp, 360.dp)) {
                    if (visible.value) {
                        androidx.compose.material3.Text("UI mount control")
                        PagedChatMessageList(presentation, ChatUiState(), callbacks, ChatContentAppearance())
                    }
                }
            }
        }
        try {
            compose.waitUntil(5_000) { started.isCompleted }
            compose.onNode(hasText("Latest optimistic message")).assertDoesNotExist()
            ready.complete(Unit)
            if (failFirst) {
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Retry")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Latest optimistic message")).assertDoesNotExist()
                compose.onNode(hasText("Retry")).performClick()
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Latest optimistic message")).fetchSemanticsNodes().isNotEmpty()
            }
            if (hasHistory) compose.onNode(hasText("Existing conversation history")).assertExists()
            compose.onNode(hasText("Latest optimistic message")).assertExists()
            if (warmReentry) {
                val loaded = loads.get()
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { visible.value = true }
                val frames = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
                repeat(8) {
                    compose.mainClock.advanceTimeByFrame()
                    fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
                    frames += Triple(exists("UI mount control"), exists("Existing conversation history"), exists("Loading conversation..."))
                }
                println("warm-reentry mount/history/loading=$frames loads-before=$loaded loads-after=${loads.get()}")
                assertEquals("Returning to a resident presentation must not reload storage", loaded, loads.get())
                assertTrue("The reentered UI must actually mount", frames.any { it.first })
                // A frame before even the static control mounts is not a rendered chat frame.
                assertTrue("Every mounted frame must contain history without loading: $frames",
                    frames.filter { it.first }.all { it.second && !it.third })
            }
        } finally {
            ready.complete(Unit)
            cacheJob.cancel()
        }
    }

    @Test
    fun longResponseRetainsZoomBetweenFingerReleaseAndSavedSettingEcho() = exerciseLongResponse(
        "Long response zoom probe. ".repeat(180),
    )

    @Test
    fun markdownResponseShrinksItsOccupiedHeightAfterZoomOut() = exerciseLongResponse(
        ("Long response zoom probe with **bold text** and `inline code` across multiple lines. ".repeat(12) +
            "\n\nLong response zoom probe with a separate paragraph and another `com.letta.mobile.dev` reference.\n\n").repeat(8),
    )

    private fun exerciseLongResponse(text: String) {
        val committed = androidx.compose.runtime.mutableFloatStateOf(1f)
        var requested = 1f
        val row: ChatRenderItem = ChatRenderItem.Single(UiMessage(
            id = "long-response", role = "assistant", content = text,
            timestamp = "2026-09-13T00:00:00Z",
        ), GroupPosition.None)
        val presentation = ChatPagingPresentation(flowOf(PagingData.from(listOf(row))), MutableStateFlow(emptyList()), {})
        val callbacks = ChatContentCallbacks(
            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
            onActiveFontScaleChange = { requested = it; committed.floatValue = it },
            onFontScaleChange = { requested = it },
        )
        compose.setContent {
            LettaChatTheme(fontScale = committed.floatValue) {
                Box(Modifier.size(320.dp, 360.dp)) {
                    PagedChatMessageList(presentation, ChatUiState(), callbacks,
                        ChatContentAppearance(activeFontScale = committed.floatValue),
                        Modifier.fillMaxSize().testTag("pinch-timeline"))
                }
            }
        }
        fun textHeight() = compose.onAllNodes(hasText("Long response zoom probe", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.size.height }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Long response zoom probe", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val baseline = textHeight()
        compose.onNodeWithTag("pinch-timeline").performTouchInput {
            down(0, center.copy(y = center.y - 60f))
            down(1, center.copy(y = center.y + 60f))
            moveTo(0, center.copy(y = center.y - 84f), delayMillis = 100)
            moveTo(1, center.copy(y = center.y + 84f), delayMillis = 100)
        }
        compose.waitForIdle()
        val zoomed = textHeight()
        assertTrue("Pinch must actually reflow the long response", zoomed > baseline * 1.1)
        compose.onNodeWithTag("pinch-timeline").performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        assertTrue("Gesture must request a new saved scale", requested > 1.1f)
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Release reverted to the old response layout before persistence", textHeight() > baseline * 1.1)
        }
        val releasedHeight = textHeight()
        compose.runOnIdle { committed.floatValue = requested }
        compose.waitForIdle()
        assertEquals("Persistence echo must not reflow again", releasedHeight, textHeight())
        val beforeShrinkBottom = compose.onAllNodes(hasText("Long response zoom probe", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        compose.onNodeWithTag("pinch-timeline").performTouchInput {
            down(0, center.copy(y = center.y - 80f))
            down(1, center.copy(y = center.y + 80f))
            moveTo(0, center.copy(y = center.y - 40f), delayMillis = 100)
            moveTo(1, center.copy(y = center.y + 40f), delayMillis = 100)
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue("Zoom-out must reduce the rendered text height", textHeight() < releasedHeight)
        val afterShrinkBottom = compose.onAllNodes(hasText("Long response zoom probe", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue("Zoom-out left stale blank height below the response: bottom $beforeShrinkBottom -> $afterShrinkBottom",
            kotlin.math.abs(beforeShrinkBottom - afterShrinkBottom) < 40f)
    }

    @Test
    fun hiddenCompletionPreservesScrolledTimelineThroughParentTicks() = exerciseCompletion(false)

    @Test
    fun completionWithEquivalentPagingGenerationPreservesScrolledTimeline() = exerciseCompletion(true)

    private fun exerciseCompletion(republishSettled: Boolean) {
        val running = ActiveSubagent("probe", "Reflection probe", "reflection", ActiveSubagent.Status.RUNNING)
        val source = object : ActiveSubagentSource {
            override val activeSubagents = MutableStateFlow<ImmutableList<ActiveSubagent>>(persistentListOf(running))
        }
        val self = object : SelfTodoSource {
            override fun selfEntry(conversationId: String) = flowOf<ActiveSubagent?>(null)
            override fun todos(conversationId: String) = emptyList<com.letta.mobile.data.model.SubagentTodo>()
        }
        val rows: List<ChatRenderItem> = (0..60).map { i ->
            ChatRenderItem.Single(UiMessage(
                id = "probe-$i", role = "user", content = "Stable timeline probe $i with enough text to wrap.",
                timestamp = "2026-09-13T00:00:00Z",
            ), GroupPosition.None)
        }
        val settled = MutableStateFlow(PagingData.from(rows))
        val presentation = ChatPagingPresentation(settled, MutableStateFlow(emptyList()), {})
        var observedAnchor: com.letta.mobile.feature.chat.screen.ChatPagingViewport? = null
        presentation.saveViewport = { observedAnchor = it }
        val callbacks = ChatContentCallbacks(
            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
        )
        val ticks = mutableSetOf<Long>()
        compose.setContent {
            LettaChatTheme {
                val state = rememberChatScreenSubagentBarState(source, self, null)
                SideEffect { ticks += state.lingerTick }
                Box(Modifier.size(320.dp, 360.dp)) {
                    PagedChatMessageList(presentation, ChatUiState(), callbacks, ChatContentAppearance(),
                        Modifier.fillMaxSize().testTag("timeline"))
                    ActiveSubagentRings(state.activeSubagents, now = state.lingerTick)
                }
            }
        }
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun visibleRows() = compose.onAllNodes(hasText("Stable timeline probe", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.id to it.boundsInRoot }
        fun pixels(): List<androidx.compose.ui.graphics.Color> {
            val image = compose.onNodeWithTag("timeline").captureToImage().toPixelMap()
            return (0 until image.height step 4).flatMap { y ->
                (0 until image.width step 4).map { x -> image[x, y] }
            }
        }
        val before = visibleRows()
        assertTrue("A populated timeline must be present", before.isNotEmpty())
        val anchor = observedAnchor
        assertTrue("The host must have received a scrolled-away anchor", anchor != null && !anchor.following)
        val baselinePixels = pixels()
        val initialTicks = ticks.size
        compose.runOnIdle { source.activeSubagents.value = persistentListOf(running.copy(
            status = ActiveSubagent.Status.COMPLETED, terminalAt = System.currentTimeMillis(),
        ))
            if (republishSettled) settled.value = PagingData.from(rows.toList())
        }
        repeat(180) { frame ->
            compose.mainClock.advanceTimeByFrame()
            assertEquals("Rows or geometry changed at frame $frame", before, visibleRows())
            assertEquals("Scroll anchor changed at frame $frame", anchor, observedAnchor)
            if (frame % 30 == 0) {
                assertEquals("Rendered timeline changed at frame $frame", baselinePixels, pixels())
            }
        }
        println("completion-timeline: 180 sampled frames, ${ticks.size - initialTicks} parent tick changes, finalAnchor=$observedAnchor")
    }
}
