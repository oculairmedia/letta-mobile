package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutoScrollSignatureTest {

    @Test
    fun `auto-scroll signature changes when newest message content grows`() {
        val before = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "assistant", content = "hel"))),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "assistant", content = "hello"))),
        )

        assertNotEquals(before, after)
    }

    @Test
    fun `auto-scroll signature ignores older page additions when newest message is unchanged`() {
        val before = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "newest", content = "hello"))),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(
                scrollTestMessage(ScrollTestMessageSpec(id = "older", content = "old")),
                scrollTestMessage(ScrollTestMessageSpec(id = "newest", content = "hello")),
            ),
        )

        assertEquals(before, after)
    }
}
