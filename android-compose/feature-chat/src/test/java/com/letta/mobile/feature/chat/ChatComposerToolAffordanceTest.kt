package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.components.ToolAffordanceRowTestTags
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ChatComposerToolAffordanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleTools() = listOf(
        Tool(id = ToolId("tool-1"), name = "fetch_url"),
        Tool(id = ToolId("tool-2"), name = "shell_exec"),
    )

    @Test
    fun `affordance row is shown when input is empty and tools are available`() {
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "",
                    pendingAttachments = persistentListOf(),
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                    availableTools = sampleTools(),
                )
            }
        }
        composeRule
            .onNodeWithTag(ToolAffordanceRowTestTags.Container)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("${ToolAffordanceRowTestTags.ChipPrefix}fetch_url")
            .assertIsDisplayed()
    }

    @Test
    fun `affordance row is hidden once user starts typing`() {
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "hello",
                    pendingAttachments = persistentListOf(),
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                    availableTools = sampleTools(),
                )
            }
        }
        composeRule
            .onNodeWithTag(ToolAffordanceRowTestTags.Container)
            .assertDoesNotExist()
    }

    @Test
    fun `affordance row is hidden when agent has no tools`() {
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "",
                    pendingAttachments = persistentListOf(),
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                    availableTools = emptyList(),
                )
            }
        }
        composeRule
            .onNodeWithTag(ToolAffordanceRowTestTags.Container)
            .assertDoesNotExist()
    }

    @Test
    fun `clicking a tool chip inserts the call-tool template into text`() {
        var captured = ""
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "",
                    pendingAttachments = persistentListOf(),
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = { captured = it },
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                    availableTools = sampleTools(),
                )
            }
        }
        composeRule
            .onNodeWithTag("${ToolAffordanceRowTestTags.ChipPrefix}fetch_url")
            .performClick()
        assertEquals("Call tool: fetch_url with parameters: ", captured)
    }
}
