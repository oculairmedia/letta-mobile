package com.letta.mobile.feature.chat.state
import com.letta.mobile.ui.chat.render.*

import com.letta.mobile.feature.chat.coordination.ChatComposerController
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

    // letta-mobile-lgns8.19: Stop marks the turn CANCELLING and keeps it
    // streaming — the terminal frame, not the request, settles the UI.
    @Test
    fun `begin cancelling keeps streaming and marks the run cancelling`() {
        val harness = Harness(
            initialState = ChatUiState(
                isStreaming = true,
                isAgentTyping = true,
                error = "previous",
            )
        )

        harness.controller.beginCancelling()

        assertEquals(true, harness.uiState.value.isStreaming)
        assertEquals(true, harness.uiState.value.isCancelling)
        assertEquals(true, harness.uiState.value.isCancellingRun)
        assertNull(harness.uiState.value.error)
    }

    @Test
    fun `force clear resets streaming flags cancelling and error`() {
        val harness = Harness(
            initialState = ChatUiState(
                isStreaming = true,
                isAgentTyping = true,
                isCancelling = true,
                error = "previous",
            )
        )

        harness.controller.forceClearStreamingAfterInterrupt()

        assertEquals(false, harness.uiState.value.isStreaming)
        assertEquals(false, harness.uiState.value.isAgentTyping)
        assertEquals(false, harness.uiState.value.isCancelling)
        assertNull(harness.uiState.value.error)
    }

    @Test
    fun `clear cancelling drops a stale cancel marker`() {
        val harness = Harness(initialState = ChatUiState(isCancelling = true))

        harness.controller.clearCancelling()

        assertEquals(false, harness.uiState.value.isCancelling)
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
