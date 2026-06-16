package com.letta.mobile.feature.chat.state

import com.letta.mobile.feature.chat.coordination.ChatComposerController
import com.letta.mobile.feature.chat.render.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatBannerControllerTest {
    @Test
    fun `show and clear error mutate conversation error only`() {
        val harness = Harness()

        harness.controller.showError("offline")
        assertEquals("offline", harness.uiState.value.error)

        harness.controller.clearError()
        assertNull(harness.uiState.value.error)
    }

    @Test
    fun `interrupt clear resets streaming flags and error`() {
        val harness = Harness(
            initialState = ChatUiState(
                isStreaming = true,
                isAgentTyping = true,
                error = "previous",
            )
        )

        harness.controller.clearStreamingAfterInterrupt()

        assertEquals(false, harness.uiState.value.isStreaming)
        assertEquals(false, harness.uiState.value.isAgentTyping)
        assertNull(harness.uiState.value.error)
    }

    @Test
    fun `composer errors delegate to composer controller`() {
        val harness = Harness()

        harness.controller.showComposerError("attach failed")
        assertEquals("attach failed", harness.composerController.state.value.error)

        harness.controller.clearComposerError()
        assertNull(harness.composerController.state.value.error)
    }

    private class Harness(initialState: ChatUiState = ChatUiState()) {
        val uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(initialState)
        val composerController = ChatComposerController()
        val controller = ChatBannerController(uiState, composerController)
    }
}
