package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.feature.chat.screen.AgentScaffoldTestTags
import com.letta.mobile.feature.chat.screen.ModelInfoCard
import com.letta.mobile.feature.chat.screen.ModelPickerSheet
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentScaffoldModelPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ModelInfoCard displays model and calls onTap`() {
        var tapCount = 0
        composeRule.setContent {
            LettaTheme {
                ModelInfoCard(
                    currentModel = "gpt-4-test",
                    onTap = { tapCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_MODEL_CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_MODEL_CARD).performClick()

        assertEquals(1, tapCount)
    }

    @Test
    fun `ModelPickerSheet selects model correctly`() {
        var selectedModel = ""
        val models = listOf(
            LlmModel(id = "1", name = "gpt-4", providerType = "openai", handle = "gpt-4"),
            LlmModel(id = "2", name = "claude-3", providerType = "anthropic", handle = "claude-3")
        )

        composeRule.setContent {
            LettaTheme {
                ModelPickerSheet(
                    models = models,
                    currentModel = "gpt-4",
                    onDismiss = {},
                    onModelSelected = { selectedModel = it },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MODEL_PICKER_SHEET).assertIsDisplayed()

        // Tap on claude-3
        composeRule.onNodeWithTag("model_row_claude-3").performClick()
        assertEquals("claude-3", selectedModel)
    }

    @Test
    fun `ModelPickerSheet survives duplicate catalog handles`() {
        // Live catalogs can list the same handle under more than one entry.
        // LazyColumn keys used to be handle-only and crashed with
        // "Key … was already used".
        var selectedModel = ""
        val models = listOf(
            LlmModel(
                id = "entry-a",
                name = "Claude Fable",
                providerType = "anthropic",
                handle = "anthropic/claude-fable-5",
            ),
            LlmModel(
                id = "entry-b",
                name = "Claude Fable (dup)",
                providerType = "anthropic",
                handle = "anthropic/claude-fable-5",
            ),
            LlmModel(
                id = "entry-c",
                name = "gpt-4",
                providerType = "openai",
                handle = "gpt-4",
            ),
        )

        composeRule.setContent {
            LettaTheme {
                ModelPickerSheet(
                    models = models,
                    currentModel = "gpt-4",
                    onDismiss = {},
                    onModelSelected = { selectedModel = it },
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MODEL_PICKER_SHEET).assertIsDisplayed()
        composeRule.onAllNodesWithTag("model_row_anthropic/claude-fable-5")[0].performClick()
        assertEquals("anthropic/claude-fable-5", selectedModel)
    }
}
