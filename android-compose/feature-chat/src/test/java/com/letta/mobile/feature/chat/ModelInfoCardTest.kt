package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.letta.mobile.feature.chat.screen.ModelInfoCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelInfoCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun formatsFallbackAcceleratorForLocalModels() {
        composeTestRule.setContent {
            ModelInfoCard(
                currentModel = "lmstudio/google/gemma-3n-e2b-it-litert-lm",
                acceleratorLabel = "CPU",
                onTap = {}
            )
        }

        composeTestRule.onNodeWithText("lmstudio/google/gemma-3n-e2b-it-litert-lm (CPU)").assertIsDisplayed()
    }

    @Test
    fun formatsFallbackAcceleratorForLocalModelsUnknown() {
        composeTestRule.setContent {
            ModelInfoCard(
                currentModel = "lmstudio/gemma",
                acceleratorLabel = "CPU",
                onTap = {}
            )
        }

        composeTestRule.onNodeWithText("lmstudio/gemma (CPU)").assertIsDisplayed()
    }

    @Test
    fun omitsAcceleratorForRemoteModels() {
        composeTestRule.setContent {
            ModelInfoCard(
                currentModel = "openai/gpt-4o",
                acceleratorLabel = "CPU",
                onTap = {}
            )
        }

        composeTestRule.onNodeWithText("openai/gpt-4o").assertIsDisplayed()
        composeTestRule.onNodeWithText("openai/gpt-4o (CPU)").assertDoesNotExist()
    }
}
