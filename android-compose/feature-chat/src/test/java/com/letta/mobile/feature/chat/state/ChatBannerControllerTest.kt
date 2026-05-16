package com.letta.mobile.feature.chat.state

import com.letta.mobile.feature.chat.ChatComposerController
import com.letta.mobile.feature.chat.ChatUiState
import com.letta.mobile.feature.chat.ClientModeConversationSwap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `conversation swap banner can be shown and dismissed`() {
        val harness = Harness()
        val swap = ClientModeConversationSwap(
            requestedConversationId = "conv-old",
            newConversationId = "conv-new",
        )

        harness.controller.showClientModeConversationSwap(swap)
        assertEquals(swap, harness.uiState.value.clientModeConversationSwap)

        harness.controller.dismissClientModeConversationSwap()
        assertNull(harness.uiState.value.clientModeConversationSwap)
    }

    @Test
    fun `client mode toggles update banner-owned session flags`() {
        val harness = Harness(
            initialState = ChatUiState(
                isStreaming = true,
                isAgentTyping = true,
                error = "previous",
            )
        )

        harness.controller.applyClientModeDisabled()
        assertFalse(harness.uiState.value.isClientModeEnabled)
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertNull(harness.uiState.value.error)

        harness.controller.applyClientModeEnabled("/workspace")
        assertTrue(harness.uiState.value.isClientModeEnabled)
        assertEquals("/workspace", harness.uiState.value.clientModeLocation.defaultPath)
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
        val uiState = MutableStateFlow(initialState)
        val composerController = ChatComposerController()
        val controller = ChatBannerController(uiState, composerController)
    }
}
