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
            isUserScrolling = false,
        )

        assertFalse(ChatViewportFollowPolicy.isNearLatest(snapshot))
        assertTrue(ChatViewportFollowPolicy.shouldShowScrollToLatest(snapshot))
        assertFalse(ChatViewportFollowPolicy.nextFollowModeAfterScroll(currentFollowMode = true, snapshot = snapshot))
    }

    @Test
    fun inProgressUserScrollDoesNotChangeFollowModeUntilItSettles() {
        val snapshot = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 20,
            isUserScrolling = true,
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

    @Test
    fun dateHeaderOffsetAppliesToEffectiveThreshold() {
        // The offset widens the effective threshold so that the user can be
        // a render item further from latest and still be considered
        // at-or-near. This compensates for the lazy-list shift that a date
        // header above the first visible render item introduces.
        // Total 40, threshold 1, lastVisible 37:
        //   offset=0 → effective=1, "near" if lastVisible >= 38. 37 < 38 → not near.
        //   offset=1 → effective=2, "near" if lastVisible >= 37. 37 >= 37 → near.
        val notNearWithoutOffset = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 37,
        )
        assertFalse(ChatViewportFollowPolicy.isNearLatest(notNearWithoutOffset))

        val nearWithOffset = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 37,
            dateHeaderOffset = 1,
        )
        assertTrue(ChatViewportFollowPolicy.isNearLatest(nearWithOffset))
        assertFalse(ChatViewportFollowPolicy.shouldShowScrollToLatest(nearWithOffset))
    }

    @Test
    fun atOrNearLatestStaysAtOrNearRegardlessOfOffset() {
        // When the user is already at the latest render item (lastVisible =
        // totalItems - 1), the offset must not flip them away from latest.
        val pinned = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 39,
            dateHeaderOffset = 1,
        )
        assertTrue(ChatViewportFollowPolicy.isNearLatest(pinned))
        assertFalse(ChatViewportFollowPolicy.shouldShowScrollToLatest(pinned))
    }

    @Test
    fun negativeDateHeaderOffsetIsClampedAndDoesNotHideFab() {
        // Defensive: a malformed negative offset must not silently turn the
        // FAB off. The policy clamps the offset to >=0 before applying it.
        val snapshot = ChatViewportSnapshot(
            totalItems = 40,
            lastVisibleIndex = 20,
            dateHeaderOffset = -3,
        )
        assertFalse(ChatViewportFollowPolicy.isNearLatest(snapshot))
        assertTrue(ChatViewportFollowPolicy.shouldShowScrollToLatest(snapshot))
    }
}
