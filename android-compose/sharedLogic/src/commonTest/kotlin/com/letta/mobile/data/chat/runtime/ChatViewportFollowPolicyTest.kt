package com.letta.mobile.data.chat.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatViewportFollowPolicyTest {
    @Test
    fun latestIndexTargetsLastItemAndHandlesEmptyLists() {
        assertEquals(0, ChatViewportFollowPolicy.latestIndex(0))
        assertEquals(0, ChatViewportFollowPolicy.latestIndex(1))
        assertEquals(8, ChatViewportFollowPolicy.latestIndex(9))
    }

    @Test
    fun nearLatestAllowsAutoFollowAndHidesAffordance() {
        val empty = ChatViewportSnapshot(totalItems = 0, lastVisibleIndex = null)
        val latest = ChatViewportSnapshot(totalItems = 10, lastVisibleIndex = 9)
        val nearlyLatest = ChatViewportSnapshot(totalItems = 10, lastVisibleIndex = 8)

        assertTrue(ChatViewportFollowPolicy.isNearLatest(empty))
        assertTrue(ChatViewportFollowPolicy.isNearLatest(latest))
        assertTrue(ChatViewportFollowPolicy.isNearLatest(nearlyLatest))
        assertFalse(ChatViewportFollowPolicy.shouldShowScrollToLatest(nearlyLatest))
    }

    @Test
    fun awayFromLatestShowsScrollAffordanceAndDisablesFollowAfterScrollStops() {
        val snapshot = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 20,
            isScrollInProgress = false,
        )

        assertFalse(ChatViewportFollowPolicy.isNearLatest(snapshot))
        assertTrue(ChatViewportFollowPolicy.shouldShowScrollToLatest(snapshot))
        assertFalse(ChatViewportFollowPolicy.nextFollowModeAfterScroll(currentFollowMode = true, snapshot = snapshot))
    }

    @Test
    fun inProgressScrollDoesNotChangeFollowModeUntilItSettles() {
        val snapshot = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 20,
            isScrollInProgress = true,
        )

        assertTrue(ChatViewportFollowPolicy.nextFollowModeAfterScroll(currentFollowMode = true, snapshot = snapshot))
        assertFalse(ChatViewportFollowPolicy.nextFollowModeAfterScroll(currentFollowMode = false, snapshot = snapshot))
    }

    @Test
    fun autoFollowRequiresFollowModeAndContent() {
        assertFalse(ChatViewportFollowPolicy.shouldAutoFollow(followMode = true, itemCount = 0))
        assertFalse(ChatViewportFollowPolicy.shouldAutoFollow(followMode = false, itemCount = 4))
        assertTrue(ChatViewportFollowPolicy.shouldAutoFollow(followMode = true, itemCount = 4))
    }
}
