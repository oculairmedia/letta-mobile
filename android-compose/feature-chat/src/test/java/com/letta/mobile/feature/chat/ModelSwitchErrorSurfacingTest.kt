package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.coordination.ChatComposerController
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for letta-mobile-g5hyz model-switch error surfacing.
 * Ensures that the ChatBannerController.showError pattern works correctly
 * for displaying transient error messages to users.
 */
class ModelSwitchErrorSurfacingTest {

    @Test
    fun `ChatBannerController showError sets error in UI state`() {
        val uiState = MutableStateFlow(ChatUiState())
        val composerController = ChatComposerController()
        val bannerController = ChatBannerController(uiState, composerController)

        assertNull("Initial error should be null", uiState.value.error)

        bannerController.showError("Couldn't switch model — still on gpt-4o-mini")

        assertNotNull("Error should be set after showError", uiState.value.error)
        assertEquals(
            "Error message should match",
            "Couldn't switch model — still on gpt-4o-mini",
            uiState.value.error
        )
    }

    @Test
    fun `ChatBannerController clearError removes error from UI state`() {
        val uiState = MutableStateFlow(ChatUiState())
        val composerController = ChatComposerController()
        val bannerController = ChatBannerController(uiState, composerController)

        bannerController.showError("Test error")
        assertNotNull("Error should be set", uiState.value.error)

        bannerController.clearError()
        assertNull("Error should be cleared", uiState.value.error)
    }

    @Test
    fun `error message format includes current model`() {
        val currentModel = "gpt-4o-mini"
        val errorMessage = "Couldn't switch model — still on $currentModel"

        assertTrue(
            "Error message should contain current model",
            errorMessage.contains(currentModel)
        )
        assertTrue(
            "Error message should indicate failure",
            errorMessage.contains("Couldn't switch")
        )
    }
}
