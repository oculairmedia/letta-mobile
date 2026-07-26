package com.letta.mobile.ui.chat.render

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSnackbarDurationTest {
    @Test
    fun retryableActionsRemainVisibleUntilHandled() {
        assertEquals(
            ChatSnackbarDuration.Indefinite,
            ChatSnackbarDuration.forRetryableAction(retryable = true),
        )
    }

    @Test
    fun nonRetryableActionsUseBriefFeedback() {
        assertEquals(
            ChatSnackbarDuration.Short,
            ChatSnackbarDuration.forRetryableAction(retryable = false),
        )
    }
}
