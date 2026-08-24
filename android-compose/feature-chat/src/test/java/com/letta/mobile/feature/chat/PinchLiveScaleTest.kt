package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.chatRenderItemSeesLiveScale
import com.letta.mobile.feature.chat.screen.messagelist.ChatPinchAnchorState
import com.letta.mobile.feature.chat.screen.messagelist.ChatVisibleItemBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchLiveScaleTest {
    private fun item(key: Any, index: Int, top: Int, bottom: Int) =
        ChatVisibleItemBounds(key, index, top, bottom)

    @Test
    fun `global live scale reaches every item in the active window`() {
        assertTrue(chatRenderItemSeesLiveScale(true, 2..4, 3))
        assertEquals(false, chatRenderItemSeesLiveScale(true, 2..4, 7))
        assertTrue(chatRenderItemSeesLiveScale(true, IntRange.EMPTY, 7))
        assertTrue(chatRenderItemSeesLiveScale(false, 2..4, 7))
    }

    @Test
    fun `anchor captures stable key and centroid offset`() {
        val state = ChatPinchAnchorState()
        val anchor = state.begin(160f, listOf(item("a", 2, 20, 120), item("b", 3, 120, 300)))

        assertEquals("b", anchor?.key)
        assertEquals(40f, anchor?.centroidOffsetPx)
        assertEquals(160f, anchor?.desiredContentPointPx)
    }

    @Test
    fun `correction sign keeps content point beneath centroid`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 3, 120, 300)))

        assertEquals(30f, state.correction(listOf(item("b", 3, 150, 360))).deltaPx)
        assertEquals(-25f, state.correction(listOf(item("b", 3, 95, 260))).deltaPx)
    }

    @Test
    fun `stable key survives reorder and resize`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 3, 120, 300)))

        val correction = state.correction(listOf(item("x", 7, 40, 90), item("b", 9, 130, 420)))

        assertEquals("b", correction.anchor?.key)
        assertEquals(10f, correction.deltaPx)
    }

    @Test
    fun `disappeared anchor falls back to nearest visible stable key`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 5, 120, 300)))

        val correction = state.correction(
            listOf(item("date-x", 4, 80, 110), item("a", 2, 110, 190), item("c", 6, 190, 280)),
        )

        assertEquals("c", correction.anchor?.key)
        assertEquals(70f, correction.deltaPx)
    }

    @Test
    fun `no stable fallback stops compensation without newest jump`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 5, 120, 300)))

        val correction = state.correction(listOf(item("date-x", 0, 0, 50), item("older-loading", 1, 50, 100)))

        assertNull(correction.anchor)
        assertEquals(0f, correction.deltaPx)
        assertNull(state.anchor)
    }

    @Test
    fun `tiny correction is ignored`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 5, 120, 300)))

        assertEquals(0f, state.correction(listOf(item("b", 5, 120, 301))).deltaPx)
    }
}
