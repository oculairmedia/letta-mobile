package com.letta.mobile.ui.chat.surface.timeline

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure decisions behind the shared timeline (letta-mobile-bglj6.1). */
class ChatTimelineLogicTest {

    private fun message(id: String, role: String = "assistant") =
        UiMessage(id = id, role = role, content = id, timestamp = "2026-09-12T10:00:00Z")

    private val ready = ChatUiState(conversationState = ConversationState.Ready("c1"), isLoadingMessages = false)

    // Phases

    @Test
    fun aConversationErrorWinsEvenOverThePagedList() {
        val failed = ready.copy(conversationState = ConversationState.Error("offline"))
        assertEquals(ChatTimelinePhase.Failed("offline"), chatTimelinePhaseOf(failed, paged = false))
        assertEquals(ChatTimelinePhase.Failed("offline"), chatTimelinePhaseOf(failed, paged = true))
    }

    @Test
    fun loadingWithNothingToShowIsTheSkeleton() {
        assertEquals(ChatTimelinePhase.Loading, chatTimelinePhaseOf(ChatUiState(), paged = false))
        assertEquals(ChatTimelinePhase.Loading, chatTimelinePhaseOf(ready.copy(isLoadingMessages = true), paged = false))
    }

    @Test
    fun anEmptyReadyConversationWelcomes() {
        assertEquals(ChatTimelinePhase.Welcome(hasConversation = true), chatTimelinePhaseOf(ready, paged = false))
    }

    @Test
    fun noConversationWelcomesWithoutAGreeting() {
        val none = ready.copy(conversationState = ConversationState.NoConversation)
        assertEquals(ChatTimelinePhase.Welcome(hasConversation = false), chatTimelinePhaseOf(none, paged = false))
    }

    @Test
    fun anyContentOrRunShowsTheList() {
        assertEquals(ChatTimelinePhase.Ready, chatTimelinePhaseOf(ready.copy(messages = persistentListOf(message("a"))), paged = false))
        assertEquals(ChatTimelinePhase.Ready, chatTimelinePhaseOf(ready.copy(isAgentTyping = true), paged = false))
        // Messages already on screen are never replaced by the skeleton during a reload.
        val reloading = ready.copy(isLoadingMessages = true, messages = persistentListOf(message("a")))
        assertEquals(ChatTimelinePhase.Ready, chatTimelinePhaseOf(reloading, paged = false))
    }

    @Test
    fun thePagedListOwnsItsOwnLoadingAndEmptyStates() {
        assertEquals(ChatTimelinePhase.Ready, chatTimelinePhaseOf(ChatUiState(), paged = true))
    }

    // Older history

    private val gate = OlderHistoryGate(hasMoreOlder = true, isLoadingOlder = false, residentMessages = 50, canRelease = true)

    @Test
    fun olderHistoryLoadsOnlyNearTheTopWhenMoreExists() {
        assertTrue(gate.shouldLoadOlder(nearOldestEdge = true))
        assertFalse(gate.shouldLoadOlder(nearOldestEdge = false))
        assertFalse(gate.copy(hasMoreOlder = false).shouldLoadOlder(true))
        assertFalse(gate.copy(isLoadingOlder = true).shouldLoadOlder(true))
        assertFalse(gate.copy(residentMessages = 0).shouldLoadOlder(true))
    }

    @Test
    fun nearTheOldestEdgeMeansWithinThreeRowsOfTheEnd() {
        assertTrue(isNearOldestEdge(oldestVisibleIndex = 97, totalItems = 100))
        assertFalse(isNearOldestEdge(oldestVisibleIndex = 96, totalItems = 100))
        assertFalse(isNearOldestEdge(oldestVisibleIndex = null, totalItems = 100))
        assertFalse(isNearOldestEdge(oldestVisibleIndex = 0, totalItems = 0))
    }

    @Test
    fun aGrownWindowIsReleasedBackNearTheNewestEdge() {
        val grown = gate.copy(residentMessages = 2_600)
        assertTrue(grown.shouldReleaseOlder(newestVisibleIndex = 5))
        assertFalse(grown.shouldReleaseOlder(newestVisibleIndex = 50))
        assertFalse(gate.shouldReleaseOlder(newestVisibleIndex = 5), "a normal window is never released")
        assertFalse(grown.copy(canRelease = false).shouldReleaseOlder(5), "only owners that page history release")
    }

    // Row binding

    @Test
    fun theStreamingIdIsTheNewestAssistantMessageWhileStreaming() {
        val messages = persistentListOf(message("u1", "user"), message("a1"), message("a2"))
        assertEquals("a2", streamingMessageIdOf(ready.copy(messages = messages, isStreaming = true)))
        assertNull(streamingMessageIdOf(ready.copy(messages = messages, isStreaming = false)))
    }

    @Test
    fun aFreshPromptLeavesThePreviousReplySettledWhileTheTurnStarts() {
        // Just sent: the run streams but nothing answers u2 yet. a2 must not replay its reveal.
        val sent = persistentListOf(message("u1", "user"), message("a1"), message("a2"), message("u2", "user"))
        assertNull(streamingMessageIdOf(ready.copy(messages = sent, isStreaming = true)))
    }

    // Pinch

    @Test
    fun pinchTracksTheGestureThenHoldsTheSnappedScaleUntilCommitted() {
        val pinch = TimelinePinchScale()
        pinch.begin(committed = 1f)
        pinch.applyZoom(1.25f)
        assertEquals(1.25f, pinch.effectiveScale(1f), 0.0001f)
        val snapped = pinch.finish()
        assertEquals(1.25f, snapped, 0.011f)
        assertEquals(snapped, pinch.effectiveScale(1f), "shown until the owner commits it")
        assertEquals(snapped, pinch.restingScale(1f), "rows hold it too, with no flash back")
        assertEquals(snapped, pinch.effectiveScale(snapped))
    }

    @Test
    fun aPinchInProgressMovesOnlyTheLayerScale() {
        val pinch = TimelinePinchScale()
        pinch.begin(committed = 1.2f)
        pinch.applyZoom(1.25f)
        assertEquals(1.2f, pinch.restingScale(1.2f), "rows keep the committed scale while pinching")
        assertEquals(1.25f, pinch.layerScale, 0.0001f)
        assertEquals(1.5f, pinch.effectiveScale(1.2f), 0.0001f)
        val snapped = pinch.finish()
        assertEquals(1f, pinch.layerScale, "the layer rests once the rows take the snapped scale")
        assertEquals(snapped, pinch.restingScale(1.2f))
    }

    @Test
    fun theLayerScaleStopsAtTheRangeEdge() {
        val pinch = TimelinePinchScale()
        pinch.begin(committed = 1.5f)
        pinch.applyZoom(2f)
        assertEquals(TimelinePinchScale.MAX_SCALE / 1.5f, pinch.layerScale, 0.0001f)
    }

    @Test
    fun aHostScaleChangeAfterACommitWins() {
        val pinch = TimelinePinchScale()
        pinch.begin(committed = 1f)
        pinch.applyZoom(1.25f)
        val snapped = pinch.finish()
        // The owner never stored the pinch, but the host moved the scale (desktop Ctrl+scroll).
        pinch.onCommittedChanged(1.4f)
        assertEquals(1.4f, pinch.effectiveScale(1.4f))
        // ...and going back to the old value does not resurrect the stale pending scale.
        pinch.onCommittedChanged(1f)
        assertEquals(1f, pinch.effectiveScale(1f))
        assertTrue(snapped > 1f)
    }

    @Test
    fun pinchClampsToTheHostRange() {
        val pinch = TimelinePinchScale(0.8f..2.0f)
        pinch.begin(committed = 1f)
        pinch.applyZoom(10f)
        assertEquals(2.0f, pinch.finish(), 0.0001f)
        pinch.begin(committed = 1f)
        pinch.applyZoom(0.01f)
        assertEquals(0.8f, pinch.finish(), 0.0001f)
    }

    @Test
    fun pinchIsClampedToTheSettingsRange() {
        val pinch = TimelinePinchScale()
        pinch.begin(committed = 1f)
        pinch.applyZoom(10f)
        assertEquals(TimelinePinchScale.MAX_SCALE, pinch.finish(), 0.0001f)
        pinch.begin(committed = 1f)
        pinch.applyZoom(0.01f)
        assertEquals(TimelinePinchScale.MIN_SCALE, pinch.finish(), 0.0001f)
    }

    // Paged

    private fun states(refresh: LoadState, endReached: Boolean) = LoadStates(
        refresh = refresh,
        prepend = LoadState.NotLoading(endReached),
        append = LoadState.NotLoading(endReached),
    ).let { CombinedLoadStates(it.refresh, it.prepend, it.append, source = it) }

    @Test
    fun pagedOpeningFollowsPagingLoadStates() {
        assertEquals(PagedOpening.Loading, pagedOpeningOf(states(LoadState.Loading, false), residentRows = 0))
        assertEquals(PagedOpening.Empty, pagedOpeningOf(states(LoadState.NotLoading(true), true), residentRows = 0))
        assertEquals(PagedOpening.Ready, pagedOpeningOf(states(LoadState.Loading, false), residentRows = 3))
        assertEquals(PagedOpening.Ready, pagedOpeningOf(states(LoadState.Error(IllegalStateException()), false), 0))
    }

    @Test
    fun aViewStillLoadingItsFirstPageReportsNoResidents() {
        val rows = listOf(settledRow("s-0"))
        // A freshly mounted pager's empty list would clear what the other views of it hold.
        assertNull(residentReport(emptyList<CanonicalTimelinePresentation.Row>(), LoadState.Loading))
        assertEquals(rows, residentReport(rows, LoadState.Loading))
        // Loaded and empty is a real report: the conversation has no settled rows.
        assertEquals(emptyList(), residentReport(emptyList<CanonicalTimelinePresentation.Row>(), LoadState.NotLoading(true)))
    }

    private fun item(id: String): ChatRenderItem =
        ChatRenderItem.Single(UiMessage(id = id, role = "assistant", content = "body", timestamp = "2026-09-12T10:00:00Z"), GroupPosition.None)

    private fun settledRow(id: String) =
        CanonicalTimelinePresentation.Row(identity = TimelineMessageId(id), revision = 1L, item = item(id))

    @Test
    fun settledRowsAreIndexedBehindTheLiveOverlay() {
        val live = listOf(item("live-0"), item("live-1"))
        val settled = listOf(settledRow("s-0"), settledRow("s-1"))
        assertEquals(2, canonicalRowIndex(live, settled, "s-0"))
        assertEquals(3, canonicalRowIndex(live, settled, "s-1"))
        assertEquals(1, canonicalRowIndex(live, emptyList(), "live-1"))
        assertNull(canonicalRowIndex(listOf(item("live-0")), listOf(settledRow("s-0")), "gone"))
    }
}
