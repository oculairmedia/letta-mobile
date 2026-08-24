package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.chatRenderItemSeesLiveScale
import com.letta.mobile.feature.chat.screen.messagelist.ChatPinchAnchorState
import com.letta.mobile.feature.chat.screen.messagelist.ChatPinchCompensationRequest
import com.letta.mobile.feature.chat.screen.messagelist.ChatVisibleItemBounds
import com.letta.mobile.feature.chat.screen.messagelist.shouldClearChatPinchAnchorAfterSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `anchor captures stable key original height and normalized point`() {
        val state = ChatPinchAnchorState()
        val anchor = state.begin(160f, listOf(item("a", 2, 20, 120), item("b", 3, 100, 200)))

        assertEquals("b", anchor?.key)
        assertEquals(100, anchor?.originalItemHeightPx)
        assertEquals(0.6f, anchor?.fractionWithinItem)
        assertEquals(160f, anchor?.desiredCentroidYPx)
    }

    @Test
    fun `grow preserves normalized content point rather than fixed offset`() {
        val state = ChatPinchAnchorState()
        state.begin(220f, listOf(item("b", 3, 100, 300)))

        assertEquals(180f, state.correction(listOf(item("b", 3, 100, 600))).deltaPx)
    }

    @Test
    fun `shrink preserves normalized content point`() {
        val state = ChatPinchAnchorState()
        state.begin(220f, listOf(item("b", 3, 100, 300)))

        assertEquals(-60f, state.correction(listOf(item("b", 3, 100, 200))).deltaPx)
    }

    @Test
    fun `stable key survives reorder and resize without fallback`() {
        val state = ChatPinchAnchorState()
        val original = state.begin(160f, listOf(item("b", 3, 120, 320)))

        val correction = state.correction(listOf(item("x", 7, 40, 90), item("b", 9, 130, 430)))

        assertEquals("b", correction.anchor?.key)
        assertSame(original, correction.anchor)
        assertEquals(30f, correction.deltaPx)
    }

    @Test
    fun `ordinary resize never falls back to another stable row`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 3, 120, 320)))

        val correction = state.correction(listOf(item("x", 2, 40, 90), item("b", 3, 100, 500)))

        assertEquals("b", correction.anchor?.key)
        assertEquals(20f, correction.deltaPx)
    }

    @Test
    fun `disappeared anchor falls back deterministically to nearest stable key`() {
        val state = ChatPinchAnchorState()
        state.begin(160f, listOf(item("b", 5, 120, 320)))

        val correction = state.correction(
            listOf(item("date-x", 4, 80, 110), item("a", 2, 110, 190), item("c", 6, 190, 290)),
        )

        assertEquals("c", correction.anchor?.key)
        assertEquals(50f, correction.deltaPx)
    }

    @Test
    fun `rapid coalesced layouts use latest normalized bounds`() {
        val state = ChatPinchAnchorState()
        state.begin(220f, listOf(item("b", 3, 100, 300)))

        state.correction(listOf(item("b", 3, 100, 400)))
        state.correction(listOf(item("b", 3, 100, 500)))
        val latest = state.correction(listOf(item("b", 3, 80, 580)))

        assertEquals(160f, latest.deltaPx)
    }

    @Test
    fun `settle residual reaches tolerance after correction`() {
        val state = ChatPinchAnchorState()
        state.begin(220f, listOf(item("b", 3, 100, 300)))

        assertEquals(180f, state.correction(listOf(item("b", 3, 100, 600))).deltaPx)
        assertEquals(0f, state.correction(listOf(item("b", 3, -80, 420))).deltaPx)
        assertEquals(0f, state.correction(listOf(item("b", 3, -79, 420))).deltaPx)
    }

    @Test
    fun `release retains anchor until committed layout settles`() {
        assertFalse(shouldClearChatPinchAnchorAfterSettle(ChatPinchCompensationRequest.Layout, true))
        assertFalse(shouldClearChatPinchAnchorAfterSettle(ChatPinchCompensationRequest.CommitLayout, false))
        assertTrue(shouldClearChatPinchAnchorAfterSettle(ChatPinchCompensationRequest.CommitLayout, true))
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
        state.begin(160f, listOf(item("b", 5, 120, 320)))

        assertEquals(0f, state.correction(listOf(item("b", 5, 120, 321))).deltaPx)
    }
}
