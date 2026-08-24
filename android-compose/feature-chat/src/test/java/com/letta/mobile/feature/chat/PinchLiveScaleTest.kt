package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.messagelist.ChatItemPinchState
import com.letta.mobile.feature.chat.screen.messagelist.ChatVisibleItemBounds
import com.letta.mobile.feature.chat.screen.messagelist.boundedOuterHeightPx
import com.letta.mobile.feature.chat.screen.messagelist.chatRenderItemSeesPinchPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchLiveScaleTest {
    private val visibleItems = listOf(
        ChatVisibleItemBounds(key = "message-a", topPx = 20, bottomPx = 120),
        ChatVisibleItemBounds(key = "run-b", topPx = 120, bottomPx = 300),
    )

    @Test
    fun `centroid selects exactly one stable owner key`() {
        val state = ChatItemPinchState()

        val owner = state.begin(centroidYPx = 160f, visibleItems = visibleItems)

        assertEquals("run-b", owner?.key)
        assertEquals(180, owner?.outerHeightPx)
        assertTrue(chatRenderItemSeesPinchPreview(owner?.key, "run-b"))
        assertFalse(chatRenderItemSeesPinchPreview(owner?.key, "message-a"))
    }

    @Test
    fun `owner key survives reordering without transferring`() {
        val state = ChatItemPinchState()
        state.begin(centroidYPx = 160f, visibleItems = visibleItems)

        assertTrue(state.reconcile(listOf("run-b", "message-a")))
        assertEquals("run-b", state.owner?.key)
        assertEquals("run-b", state.begin(40f, visibleItems)?.key)
    }

    @Test
    fun `missing owner cancels rather than transferring`() {
        val state = ChatItemPinchState()
        state.begin(centroidYPx = 160f, visibleItems = visibleItems)

        assertFalse(state.reconcile(listOf("message-a", "new-item")))
        assertNull(state.owner)
        assertFalse(chatRenderItemSeesPinchPreview(state.owner?.key, "message-a"))
    }

    @Test
    fun `only owner receives its captured fixed outer height`() {
        val state = ChatItemPinchState()
        val owner = state.begin(centroidYPx = 160f, visibleItems = visibleItems)

        assertEquals(180, boundedOuterHeightPx(owner, "run-b"))
        assertNull(boundedOuterHeightPx(owner, "message-a"))
    }

    @Test
    fun `centroid outside visible rows establishes no owner`() {
        val state = ChatItemPinchState()

        assertNull(state.begin(centroidYPx = 400f, visibleItems = visibleItems))
        assertNull(state.owner)
    }
}
