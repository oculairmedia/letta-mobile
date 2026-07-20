package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import com.letta.mobile.feature.chat.screen.shouldForceScrollOnUserSend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSendForceScrollTest {

    @Test
    fun `force scroll on user send brings new user bubble into view from any position`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "u-new", content = "hello there", role = ScrollTestMessageRole(value = "user")))),
        )!!

        assertTrue(
            shouldForceScrollOnUserSend(
                signature = signature,
                previousNewestMessageId = "assistant-prev",
            ),
        )
    }

    @Test
    fun `force scroll on user send does not retrigger for same user message`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "u-new", content = "hello there", role = ScrollTestMessageRole(value = "user")))),
        )!!

        assertFalse(
            shouldForceScrollOnUserSend(
                signature = signature,
                previousNewestMessageId = "u-new",
            ),
        )
    }

    @Test
    fun `force scroll on user send ignores assistant streaming updates`() {
        val signature = newestMessageAutoScrollSignature(
            listOf(scrollTestMessage(ScrollTestMessageSpec(id = "assistant-new", content = "streaming"))),
        )!!

        assertFalse(
            shouldForceScrollOnUserSend(
                signature = signature,
                previousNewestMessageId = "user-prev",
            ),
        )
    }
}
