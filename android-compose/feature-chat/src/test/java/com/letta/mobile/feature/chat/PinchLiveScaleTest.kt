package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.chatRenderItemSeesLiveScale
import org.junit.Assert.assertEquals
import org.junit.Test

class PinchLiveScaleTest {

    @Test
    fun `pinch live scale follows visible window regardless of expensive content`() {
        val visibleWindow = 2..4

        assertEquals(
            true,
            chatRenderItemSeesLiveScale(
                isPinching = true,
                scaleWindowIndexRange = visibleWindow,
                itemIndex = 3,
            ),
        )
        assertEquals(
            false,
            chatRenderItemSeesLiveScale(
                isPinching = true,
                scaleWindowIndexRange = visibleWindow,
                itemIndex = 7,
            ),
        )
    }

    @Test
    fun `pinch live scale includes all items before visibility window is known`() {
        assertEquals(
            true,
            chatRenderItemSeesLiveScale(
                isPinching = true,
                scaleWindowIndexRange = IntRange.EMPTY,
                itemIndex = 7,
            ),
        )
    }

    @Test
    fun `non-pinching items always see committed live scale path`() {
        assertEquals(
            true,
            chatRenderItemSeesLiveScale(
                isPinching = false,
                scaleWindowIndexRange = 2..4,
                itemIndex = 7,
            ),
        )
    }
}
