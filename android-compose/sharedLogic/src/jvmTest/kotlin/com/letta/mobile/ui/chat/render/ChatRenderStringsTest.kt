package com.letta.mobile.ui.chat.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRenderStringsTest {
    @Test
    fun formatsToolOutputLimitNotices() {
        assertEquals("5 more characters not shown", ChatRenderStrings.charsOmitted(5))
        assertEquals("3 more lines not shown", ChatRenderStrings.linesOmitted(3))
        assertEquals("8 more characters not analyzed", ChatRenderStrings.truncated(8))
        assertEquals("file", ChatRenderStrings.diffFile())
    }
}
