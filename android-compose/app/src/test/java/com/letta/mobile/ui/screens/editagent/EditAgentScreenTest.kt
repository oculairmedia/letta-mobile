package com.letta.mobile.ui.screens.editagent

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ToolId
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.test.setLettaTestContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class EditAgentScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun advancedSettingsSectionsRenderAndRemainScrollable() {
        val viewModel = mockk<EditAgentViewModel>(relaxed = true)
        val uiState = MutableStateFlow(UiState.Success(advancedState()))
        val llmModels = MutableStateFlow(
            listOf(
                LlmModel(
                    id = "model-1",
                    name = "GPT 5 Mini",
                    handle = "openai/gpt-5-mini",
                    providerType = "openai",
                    contextWindow = 128_000,
                ),
                LlmModel(
                    id = "model-2",
                    name = "Claude Sonnet",
                    handle = "anthropic/claude-3-5-sonnet",
                    providerType = "anthropic",
                    contextWindow = 200_000,
                ),
            )
        )
        val embeddingModels = MutableStateFlow(
            listOf(
                EmbeddingModel(
                    id = "embedding-1",
                    name = "Text Embedding 3 Small",
                    handle = "openai/text-embedding-3-small",
                    providerType = "openai",
                )
            )
        )
        every { viewModel.uiState } returns uiState
        every { viewModel.llmModels } returns llmModels
        every { viewModel.embeddingModels } returns embeddingModels

        composeRule.setLettaTestContent(useChatTheme = false) {
            EditAgentScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithText("Identity").assertIsDisplayed()
        composeRule.onNodeWithText("Name").assertIsDisplayed()

        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(5)
        composeRule.onNodeWithText("Models").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(7)
        composeRule.onNodeWithText("LLM Configuration").assertIsDisplayed()
        composeRule.onAllNodesWithText("Embedding Model").assertCountEquals(1)

        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(17)
        composeRule.onNodeWithText("Runtime").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(18)
        composeRule.onNodeWithText("LettaBot Client Mode").assertIsDisplayed()

        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(8)
        composeRule.onNodeWithText("Advanced").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(9)
        composeRule.onNodeWithText("Primary Model Advanced").assertIsDisplayed()
        listOf(
            "Provider Name",
            "Provider Category",
            "Enable Reasoner",
            "Reasoning Effort",
            "Max Reasoning Tokens",
            "Reasoning JSON",
            "Response Format JSON",
            "Response Schema JSON",
            "Thinking Config JSON",
            "Strict Tool Calling",
            "Tool Call Parser",
            "Anthropic Effort",
        ).forEach { label ->
            composeRule.onAllNodesWithText(label, substring = true).assertCountEquals(1)
        }

        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(10)
        composeRule.onNodeWithText("Memory").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(11)
        listOf(
            "Compaction Mode",
            "Summarizer Model",
            "Model Settings JSON",
            "Summarization Prompt",
            "Clip Characters",
            "Sliding Window Percentage",
            "Prompt Acknowledgement",
        ).forEach { label ->
            composeRule.onAllNodesWithText(label, substring = true).assertCountEquals(1)
        }

        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(13)
        composeRule.onNodeWithText("Tools").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(15)
        composeRule.onNodeWithText("Tools (1)").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(16)
        composeRule.onNodeWithText("Tool Rules / Approval Policy").assertIsDisplayed()
        composeRule.onNodeWithText("Tool Rules JSON").assertIsDisplayed()
    }

    @Test
    fun validationWarningMarksContainingSectionAndSectionRemainsScrollable() {
        val viewModel = mockk<EditAgentViewModel>(relaxed = true)
        val editState = advancedState().copy(toolRulesJson = "{not-an-array")
        val uiState = MutableStateFlow(
            UiState.Success(
                editState
            )
        )
        val llmModels = MutableStateFlow(emptyList<LlmModel>())
        val embeddingModels = MutableStateFlow(emptyList<EmbeddingModel>())
        every { viewModel.uiState } returns uiState
        every { viewModel.llmModels } returns llmModels
        every { viewModel.embeddingModels } returns embeddingModels

        composeRule.setLettaTestContent(useChatTheme = false) {
            EditAgentScreen(
                onNavigateBack = {},
                viewModel = viewModel,
            )
        }

        assertTrue(EditAgentConfigTab.Tools.hasValidationWarning(editState))
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(13)
        composeRule.onNodeWithText("Tools").assertIsDisplayed()
        composeRule.onNodeWithTag(EditAgentTestTags.CONTENT_LIST).performScrollToIndex(16)
        composeRule.onNodeWithText("Tool Rules JSON").assertIsDisplayed()
    }

    private fun advancedState() = EditAgentUiState(
        agent = Agent(
            id = AgentId("agent-advanced"),
            name = "Advanced Agent",
            model = "openai/gpt-5-mini",
            embedding = "openai/text-embedding-3-small",
            agentType = "stateful",
        ),
        agentId = "agent-advanced",
        name = "Advanced Agent",
        model = "openai/gpt-5-mini",
        embedding = "openai/text-embedding-3-small",
        providerType = "openai",
        modelProviderName = "OpenAI",
        modelProviderCategory = "cloud",
        modelEnableReasoner = true,
        modelReasoningEffort = "high",
        modelMaxReasoningTokens = "2048",
        modelReasoningJson = "{\"summary\":\"auto\"}",
        modelResponseFormatJson = "{\"type\":\"json_object\"}",
        modelResponseSchemaJson = "{\"name\":\"handoff_summary\"}",
        modelThinkingConfigJson = "{\"budget_tokens\":1024}",
        modelStrictToolCalling = true,
        modelToolCallParser = "openai-tools",
        modelAnthropicEffort = "medium",
        attachedTools = kotlinx.collections.immutable.persistentListOf(
            com.letta.mobile.data.model.Tool(id = ToolId("tool-shell"), name = "shell"),
        ),
        toolRulesJson = "[{\"type\":\"requires_approval\",\"tool_name\":\"shell\"}]",
        compactionMode = "self_compact_all",
        compactionModel = "anthropic/claude-3-5-sonnet",
        compactionModelSettingsJson = "{\"max_output_tokens\":1024}",
        summarizationPrompt = "Keep decisions, tasks, and blockers.",
        compactionClipChars = 24_000,
        slidingWindowPercentage = 0.35f,
        promptAcknowledgement = true,
    )
}
