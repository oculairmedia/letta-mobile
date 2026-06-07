package com.letta.mobile.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopChatScrollStateTest {
    @Test
    fun initialAnchorTargetsLatestMessageIndex() {
        assertEquals(0, latestMessageScrollIndex(0))
        assertEquals(0, latestMessageScrollIndex(1))
        assertEquals(8, latestMessageScrollIndex(9))
    }

    @Test
    fun nearLatestAllowsAutoFollow() {
        assertTrue(isNearLatestMessage(totalItems = 0, lastVisibleIndex = 0))
        assertTrue(isNearLatestMessage(totalItems = 10, lastVisibleIndex = 9))
        assertTrue(isNearLatestMessage(totalItems = 10, lastVisibleIndex = 8))
    }

    @Test
    fun awayFromLatestShowsScrollAffordanceAndDisablesAutoFollow() {
        assertFalse(isNearLatestMessage(totalItems = 10, lastVisibleIndex = 7))
        assertFalse(isNearLatestMessage(totalItems = 40, lastVisibleIndex = 20))
    }
}
