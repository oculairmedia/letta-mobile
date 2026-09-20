package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.letta.mobile.feature.chat.screen.TimelineOpeningState
import com.letta.mobile.feature.chat.screen.deriveTimelineOpeningState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.LocalTimelineOpeningObserver
import com.letta.mobile.feature.chat.screen.PagedChatMessageList
import com.letta.mobile.feature.chat.screen.TimelineOpeningObservation
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.feature.chat.TimelineOpeningRecorder.Milestone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TimelineOpeningObservationTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String) = ChatRenderItem.Single(
        UiMessage(id = id, role = "user", content = id, timestamp = "2026-09-07T00:00:00Z"),
        GroupPosition.None,
    )

    private enum class Boundary { Terminal, Open }

    /**
     * Test-local Paging source boundaries. With reverseLayout, Paging prepend is the newest edge
     * (the source's prevKey) and append is the older edge (the source's nextKey).
     */
    private data class PageBoundaries(
        val newest: Boundary,
        val older: Boundary,
    ) {
        private fun Boundary.loadState() = LoadState.NotLoading(this == Boundary.Terminal)

        fun loadStates(refresh: LoadState) = LoadStates(
            refresh = refresh,
            prepend = newest.loadState(),
            append = older.loadState(),
        )

        val isCompleteHistory get() = newest == Boundary.Terminal && older == Boundary.Terminal

        companion object {
            val Complete = PageBoundaries(Boundary.Terminal, Boundary.Terminal)
            val NewestOpen = PageBoundaries(Boundary.Open, Boundary.Terminal)
        }
    }

    /** Channel holds page delivery without sleeps; opening, page, anchor and freshness are separate. */
    private inner class Fixture {
        val rows = listOf(row("first"), row("second"), row("third"))
        // visibleItemsInfo iteration order for this reverseLayout list, not screen top-to-bottom.
        // residentPageReachesBoundedAnchoredViewportWithoutFreshness verifies the actual callback.
        val expectedVisibleKeys = listOf(rows[0].key, rows[1].key, rows[2].key)
        val recorder = TimelineOpeningRecorder(expectedVisibleKeys, rows.first().key).also {
            it.mark(Milestone.Selection)
        }
        val delivery = Channel<PagingData<ChatRenderItem>>(Channel.UNLIMITED)
        var generations = 0
        val shellMounts = mutableListOf<com.letta.mobile.feature.chat.screen.TimelineShellToken>()
        val shellDisposals = mutableListOf<com.letta.mobile.feature.chat.screen.TimelineShellToken>()
        val readiness = mutableListOf<Pair<androidx.compose.foundation.lazy.LazyListState, Boolean>>()
        val mounts = mutableListOf<String>()
        val disposals = mutableListOf<String>()
        // A fixture-only boundary; no claim about canonical transport or process-cold IO.
        val freshness = kotlinx.coroutines.CompletableDeferred<Unit>()
        val settled = delivery.receiveAsFlow().onEach {
            generations++
            recorder.mark(Milestone.FirstGeneration)
        }
        val opened = ChatPagingPresentation(settled, MutableStateFlow(emptyList()), {})
        val current = mutableStateOf(ChatPagingPresentation(settled, MutableStateFlow(emptyList()), {}, opening = true))
        fun open() {
            recorder.mark(Milestone.PresentationOpen)
            current.value = opened
        }
        fun page(
            items: List<ChatRenderItem>,
            boundaries: PageBoundaries,
            refresh: LoadState = LoadState.NotLoading(false),
        ) {
            check(delivery.trySend(PagingData.from(items, boundaries.loadStates(refresh))).isSuccess)
        }
        fun mount(modifier: Modifier = Modifier) {
            recorder.mark(Milestone.Selection)
            compose.setContent {
                LettaChatTheme {
                    CompositionLocalProvider(
                        LocalTimelineOpeningObserver provides recorder::observe,
                        com.letta.mobile.feature.chat.screen.LocalTimelineReadinessObserver provides { state, ready ->
                            readiness += state to ready
                        },
                        com.letta.mobile.feature.chat.screen.LocalTimelineRowLifecycleObserver provides { event, key ->
                            if (event == com.letta.mobile.feature.chat.screen.TimelineRowLifecycle.Mount) mounts += key
                            else disposals += key
                        },
                        com.letta.mobile.feature.chat.screen.LocalTimelineShellLifecycleObserver provides { event ->
                            when (event) {
                                is com.letta.mobile.feature.chat.screen.TimelineShellLifecycle.Mounted -> shellMounts += event.token
                                is com.letta.mobile.feature.chat.screen.TimelineShellLifecycle.Disposed -> shellDisposals += event.token
                            }
                        },
                    ) {
                        PagedChatMessageList(current.value, ChatUiState(), ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        ), ChatContentAppearance(), modifier)
                    }
                }
            }
        }
        fun surfaces() = recorder.observations.mapNotNull {
            (it.observation as? TimelineOpeningObservation.Committed)?.surface
        }.distinct()
        fun milestones() = recorder.milestones.map { it.second }
    }

    @Test fun readyRevealKeepsViewportStateAndMountedKeys() {
        val f = Fixture()
        f.open()
        f.page(f.rows, PageBoundaries.NewestOpen)
        f.mount(Modifier.size(300.dp, 400.dp))
        compose.onNodeWithText("first").assertDoesNotExist()
        val before = compose.runOnIdle {
            assertEquals(f.expectedVisibleKeys.toSet(), f.mounts.toSet())
            assertTrue(f.readiness.none { it.second })
            val layout = f.readiness.last().first.layoutInfo
            assertEquals(f.expectedVisibleKeys, layout.visibleItemsInfo.map { it.key })
            assertTrue(layout.viewportSize.width > 0 && layout.viewportSize.height > 0)
            // onGloballyPositioned proves placement, not merely lazy measure metadata.
            assertTrue(f.recorder.observations.any {
                val measured = it.observation as? TimelineOpeningObservation.Layout
                measured?.rows?.map { row -> row.key } == f.expectedVisibleKeys
            })
            f.readiness.last().first
        }
        compose.runOnIdle { f.page(f.rows, PageBoundaries.Complete) }
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(f.readiness.all { it.first === before })
            assertEquals(f.expectedVisibleKeys.toSet(), f.mounts.toSet())
            assertEquals(3, f.mounts.size)
            assertTrue(f.disposals.isEmpty())
            assertEquals(1, f.readiness.map { it.second }.distinct().count { it })
        }
    }

    @Test fun olderContinuationDoesNotMoveInitialAnchor() {
        val f = Fixture()
        f.open()
        f.page(f.rows, PageBoundaries(Boundary.Terminal, Boundary.Open))
        f.mount(Modifier.size(300.dp, 200.dp))
        compose.onNodeWithText("first").assertIsDisplayed()
        val before = compose.runOnIdle {
            f.readiness.last().first.layoutInfo.visibleItemsInfo.map { it.key to it.offset }
        }
        compose.runOnIdle { f.page(f.rows + (1..20).map { row("older-$it") }, PageBoundaries.Complete) }
        compose.runOnIdle {
            assertEquals(before, f.readiness.last().first.layoutInfo.visibleItemsInfo.map { it.key to it.offset })
        }
    }

    @Test fun routeTargetWaitsForResidentAppliedAnchorWithOpenBoundaries() = requestedAnchorReveals(saved = false)

    @Test fun savedViewportWaitsForResidentAppliedAnchorWithOpenBoundaries() = requestedAnchorReveals(saved = true)

    private fun requestedAnchorReveals(saved: Boolean) {
        val f = Fixture()
        if (saved) f.opened.viewport = com.letta.mobile.feature.chat.screen.ChatPagingViewport("third", 7)
        else {
            f.opened.hasBoundRoute = true
            f.opened.routeTarget = "third"
        }
        f.open()
        f.page(f.rows.take(1), PageBoundaries(Boundary.Open, Boundary.Open))
        f.mount(Modifier.size(300.dp, 200.dp))
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.runOnIdle { assertTrue(f.readiness.none { it.second }) }
        compose.runOnIdle { f.page(f.rows + (1..20).map { row("older-$it") }, PageBoundaries(Boundary.Open, Boundary.Open)) }
        compose.onNodeWithText("third").assertIsDisplayed()
        compose.runOnIdle {
            val layout = f.readiness.last().first.layoutInfo
            val target = layout.visibleItemsInfo.first { it.key == f.rows[2].key }
            if (saved) assertEquals(-7, target.offset)
            else assertEquals((layout.viewportEndOffset - layout.viewportStartOffset - target.size) / 2, target.offset)
            assertTrue(f.readiness.last().second)
        }
    }

    @Test fun terminalOneMessageReveals() {
        val f = Fixture()
        f.open()
        f.page(f.rows.take(1), PageBoundaries.Complete)
        f.mount()
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithText("Loading conversation...").assertDoesNotExist()
    }

    @Test fun callerModifierConstrainsLoadingAndTimelineContentExactlyOnce() {
        val f = Fixture()
        f.mount(Modifier.size(300.dp, 240.dp).testTag("caller-viewport").padding(20.dp))
        fun assertContent(matcher: SemanticsMatcher) {
            compose.onNode(matcher and hasAnyAncestor(hasTestTag("caller-viewport")))
                .assertIsDisplayed()
                .assertWidthIsEqualTo(260.dp)
                .assertHeightIsEqualTo(200.dp)
        }
        fun assertLoading() = assertContent(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)
            and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        assertLoading()
        compose.runOnIdle { f.open() }
        assertLoading()
        compose.runOnIdle { f.page(f.rows.take(1), PageBoundaries.NewestOpen) }
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle { f.page(f.rows, PageBoundaries.Complete) }
        assertContent(hasScrollToIndexAction())
        compose.onNodeWithTag("caller-viewport").assertWidthIsEqualTo(300.dp).assertHeightIsEqualTo(240.dp)
    }

    @Test fun cachedOpenFirstVisibleFrameIsCompleteAndAnchored() {
        val f = Fixture()
        f.open()
        f.page(f.rows, PageBoundaries.Complete)
        f.recorder.resolveAnchor()
        f.mount()
        compose.onNodeWithText("third").assertIsDisplayed()
        compose.runOnIdle {
            val measured = f.recorder.observations.map { it.observation }
                .filterIsInstance<TimelineOpeningObservation.Layout>().last()
            assertEquals("raw visibleItemsInfo order with reverseLayout=true",
                f.expectedVisibleKeys, measured.rows.map { it.key })
            assertEquals("newest-edge anchor offset", 0, measured.rows.first().offset)
            assertTrue("reverse-layout offsets increase away from the newest edge",
                measured.rows.zipWithNext().all { (a, b) -> a.offset < b.offset })
            assertEquals(listOf(Milestone.Selection, Milestone.PresentationOpen, Milestone.FirstGeneration,
                Milestone.ViewportReady, Milestone.FirstContentCommitted), f.milestones())
            assertEquals(1, f.generations)
            assertFalse(f.freshness.isCompleted)
        }
    }

    @Test fun heldOneRowGenerationExposesLoadingBeforeCompleteViewport() {
        val f = Fixture()
        f.mount()
        compose.onNodeWithText("Opening conversation...").assertIsDisplayed()
        compose.runOnIdle { f.open() }
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle { f.page(f.rows.take(1), PageBoundaries.NewestOpen) }
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(Milestone.FirstGeneration in f.milestones())
            // S1 records placement, not drawing: hidden rows now legitimately lay out.
            assertTrue(Milestone.FirstContentCommitted in f.milestones())
            assertTrue(f.readiness.none { it.second })
            assertFalse(Milestone.ViewportReady in f.milestones())
            f.page(f.rows, PageBoundaries.Complete)
        }
        compose.onNodeWithText("third").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse("rows alone do not resolve an anchor", Milestone.ViewportReady in f.milestones())
            f.recorder.resolveAnchor()
            assertEquals(listOf(Milestone.Selection, Milestone.PresentationOpen, Milestone.FirstGeneration,
                Milestone.FirstContentCommitted, Milestone.ViewportReady), f.milestones())
            assertEquals(listOf(TimelineOpeningObservation.Surface.Opening,
                TimelineOpeningObservation.Surface.InitialLoading, TimelineOpeningObservation.Surface.Timeline), f.surfaces())
            assertFalse(f.freshness.isCompleted)
        }
    }

    @Test fun coldIndexedLikePageReleaseDoesNotWaitForFixtureFreshness() {
        // Models delayed local delivery only; it does not launch a process or exercise disk IO.
        val f = Fixture()
        f.mount()
        compose.runOnIdle { f.open() }
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle {
            f.recorder.resolveAnchor()
            f.page(f.rows, PageBoundaries.Complete)
        }
        compose.onNodeWithText("third").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Milestone.Selection, Milestone.PresentationOpen, Milestone.FirstGeneration,
                Milestone.ViewportReady, Milestone.FirstContentCommitted), f.milestones())
            val before = f.milestones()
            assertFalse(f.freshness.isCompleted)
            f.freshness.complete(Unit)
            assertEquals(before, f.milestones())
            assertEquals(1, f.generations)
        }
    }

    @Test fun emissionOneRowAndWrongAnchorCannotCountAsCompleteViewport() {
        val recorder = TimelineOpeningRecorder(listOf("a", "b"), "a", anchorOffset = 7)
        recorder.mark(Milestone.Selection)
        recorder.mark(Milestone.PresentationOpen)
        recorder.mark(Milestone.FirstGeneration)
        recorder.resolveAnchor()
        // Same raw visibleItemsInfo iteration order as the reverse-layout Compose fixture.
        // Offsets increase away from the newest edge; do not reverse into screen order.
        fun layout(vararg rows: TimelineOpeningObservation.VisibleRow) {
            recorder.observe(TimelineOpeningObservation.Layout(rows.toList(), 0, 100))
        }
        assertFalse(recorder.milestones.any { it.second == Milestone.ViewportReady })
        layout(TimelineOpeningObservation.VisibleRow("a", 7, 20))
        assertFalse(recorder.milestones.any { it.second == Milestone.ViewportReady })
        layout(TimelineOpeningObservation.VisibleRow("a", 0, 20), TimelineOpeningObservation.VisibleRow("b", 20, 20))
        assertFalse(recorder.milestones.any { it.second == Milestone.ViewportReady })
        layout(TimelineOpeningObservation.VisibleRow("a", 7, 20), TimelineOpeningObservation.VisibleRow("b", 120, 20))
        assertFalse(recorder.milestones.any { it.second == Milestone.ViewportReady })
        repeat(2) {
            layout(TimelineOpeningObservation.VisibleRow("a", 7, 20), TimelineOpeningObservation.VisibleRow("b", 27, 20))
        }
        assertEquals(1, recorder.milestones.count { it.second == Milestone.ViewportReady })
        assertEquals(5, recorder.observations.size) // No coalescing of repeated layout states.
    }

    @Test fun reorderedExpectedKeysCannotCountAsCompleteViewport() {
        assertWrongVisibleWindow(listOf("a", "c", "b"))
    }

    @Test fun unexpectedInterleavedRowCannotCountAsCompleteViewport() {
        assertWrongVisibleWindow(listOf("a", "interloper", "b", "c"))
    }

    @Test fun duplicateVisibleRowCannotCountAsCompleteViewport() {
        assertWrongVisibleWindow(listOf("a", "b", "b", "c"))
    }

    private fun assertWrongVisibleWindow(keys: List<String>) {
        val expected = listOf("a", "b", "c")
        val recorder = TimelineOpeningRecorder(expected, "a", anchorOffset = 7)
        recorder.resolveAnchor()
        // Raw visibleItemsInfo order, with increasing reverse-layout offsets just as above.
        fun layout(window: List<String>) = TimelineOpeningObservation.Layout(
            window.mapIndexed { index, key -> TimelineOpeningObservation.VisibleRow(key, 7 + index * 20, 20) },
            0, 100,
        )
        recorder.observe(layout(keys))
        assertFalse("wrong ordered window $keys must not be ready",
            recorder.milestones.any { it.second == Milestone.ViewportReady })
        recorder.observe(layout(expected))
        assertEquals(1, recorder.milestones.count { it.second == Milestone.ViewportReady })
    }

    @Test fun typedReadinessUsesPagingBoundariesRatherThanRowCount() {
        val incompleteOneRow = PageBoundaries.NewestOpen
        val completedOneMessage = PageBoundaries.Complete
        // Paging boundary state, not the number of resident rows, establishes fixture history.
        assertFalse(incompleteOneRow.isCompleteHistory)
        assertTrue(completedOneMessage.isCompleteHistory)
        assertEquals(TimelineOpeningState.Priming, deriveTimelineOpeningState(
            opening = false,
            openError = null,
            historyReady = incompleteOneRow.isCompleteHistory,
        ))
        assertEquals(TimelineOpeningState.Ready, deriveTimelineOpeningState(
            opening = false,
            openError = null,
            historyReady = completedOneMessage.isCompleteHistory,
        ))
        assertEquals(TimelineOpeningState.Opening, deriveTimelineOpeningState(true, null))
        assertEquals(TimelineOpeningState.Priming, deriveTimelineOpeningState(false, null,
            refresh = LoadState.NotLoading(false)))
        assertEquals(TimelineOpeningState.Empty, deriveTimelineOpeningState(false, null,
            historyReady = true, confirmedEmpty = true))
        assertEquals(TimelineOpeningState.Failed("open failed", true), deriveTimelineOpeningState(true, "open failed"))
        assertEquals(TimelineOpeningState.Failed("Could not load conversation", false),
            deriveTimelineOpeningState(false, null, refresh = LoadState.Error(IllegalStateException())))
    }

    @Test fun actualShellNodeSurvivesOpeningFailurePrimingEmptyAndResidentRows() {
        val f = Fixture()
        f.mount()
        // Semantics IDs belong to the actual mounted layout node, not an observer outside it.
        // Replacing the Box/root on any branch produces a different ID and fails this probe.
        fun shellId(): Int {
            val id = compose.onNodeWithTag("timeline-opening-shell").fetchSemanticsNode().id
            compose.runOnIdle {
                assertEquals(1, f.shellMounts.size)
                assertTrue(f.shellDisposals.isEmpty())
            }
            return id
        }
        val original = shellId()
        fun loading() = compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
            and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertIsDisplayed()
        loading()
        var retries = 0
        compose.runOnIdle {
            f.current.value = ChatPagingPresentation(f.settled, MutableStateFlow(emptyList()), {},
                openError = "open failed", retryOpen = { retries++ })
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "open failed")).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertHasClickAction().performClick()
        assertEquals(1, retries)
        assertEquals(original, shellId())
        compose.runOnIdle { f.open() }
        loading()
        assertEquals(original, shellId())
        compose.runOnIdle { f.page(emptyList(), PageBoundaries.Complete, LoadState.Loading) }
        loading()
        compose.onNodeWithText("No messages yet").assertDoesNotExist()
        compose.runOnIdle { f.page(emptyList(), PageBoundaries.Complete, LoadState.Error(IllegalStateException())) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Could not load conversation")).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertHasClickAction().performClick()
        assertEquals(original, shellId())
        compose.runOnIdle { f.page(emptyList(), PageBoundaries.Complete) }
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        assertEquals(original, shellId())
        compose.runOnIdle { f.page(f.rows.take(1), PageBoundaries.NewestOpen) }
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle { f.page(f.rows, PageBoundaries.Complete) }
        compose.onNodeWithText("No messages yet").assertDoesNotExist()
        assertEquals(original, shellId())
        compose.runOnIdle { f.page(f.rows, PageBoundaries.Complete) }
        compose.onNodeWithText("third").assertIsDisplayed()
        assertEquals(original, shellId())
    }

    @Test fun confirmedEmptyAddsExplicitTreatmentAndRetainsS1EmptyObservation() {
        val f = Fixture()
        f.mount()
        compose.runOnIdle { f.open() }
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle { f.page(emptyList(), PageBoundaries.Complete) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(Milestone.Selection, Milestone.PresentationOpen, Milestone.FirstGeneration,
                Milestone.Empty), f.milestones())
            assertEquals(TimelineOpeningObservation.Surface.Timeline, f.surfaces().last())
        }
    }

    @Test fun initialPageFailureRecordsTerminalFailureAndExistingRetry() {
        val f = Fixture()
        f.mount()
        compose.runOnIdle { f.open() }
        compose.onNodeWithText("Loading conversation...").assertIsDisplayed()
        compose.runOnIdle { f.page(
                emptyList(),
                PageBoundaries.Complete,
                LoadState.Error(IllegalStateException("held failure")),
            ) }
        compose.onNodeWithText("Could not load conversation").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Milestone.Selection, Milestone.PresentationOpen, Milestone.FirstGeneration,
                Milestone.Failed), f.milestones())
            assertTrue(f.readiness.none { it.second })
            assertTrue(f.mounts.isEmpty())
        }
    }
}
