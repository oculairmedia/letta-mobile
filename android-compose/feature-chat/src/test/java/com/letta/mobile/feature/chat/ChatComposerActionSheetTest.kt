package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.feature.chat.screen.ChatComposer
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ChatComposerActionSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val tool = Tool(
        id = ToolId("tool-1"),
        name = "fetch_url",
    )

    private data class ComposerScenario(
        val inputText: String = "",
        val pendingAttachments: ImmutableList<MessageContentPart.Image> = persistentListOf(),
        val onTextChange: (String) -> Unit = {},
        val onAttachImage: () -> Unit = {},
        val availableTools: List<Tool>? = null,
    )

    @Test
    fun `plus opens attach and tool actions for an empty draft`() {
        setComposer()

        openComposerActions()

        composeRule.onNodeWithText("Add to message").assertIsDisplayed()
        composeRule.onNodeWithText("Attach image").assertIsDisplayed()
        composeRule.onAllNodesWithText(tool.name).assertCountEquals(2)
        composeRule.onNodeWithText("Insert tool template").assertIsDisplayed()
    }

    @Test
    fun `tool action remains reachable for a non-empty draft`() {
        setComposer(ComposerScenario(inputText = "text draft"))

        openComposerActions()

        composeRule.onNodeWithText(tool.name).assertIsDisplayed()
    }

    @Test
    fun `tool action remains reachable with an attachment`() {
        setComposer(
            ComposerScenario(
                pendingAttachments = persistentListOf(
                    MessageContentPart.Image(
                        base64 = "",
                        mediaType = "image/png",
                    ),
                ),
            ),
        )

        openComposerActions()

        composeRule.onNodeWithText(tool.name).assertIsDisplayed()
    }

    @Test
    fun `selecting a tool inserts the existing template and closes the sheet`() {
        var capturedText = "existing draft"
        setComposer(
            ComposerScenario(
                inputText = capturedText,
                onTextChange = { capturedText = it },
            ),
        )

        openComposerActions()
        composeRule.onNodeWithText(tool.name).performClick()

        assertEquals(
            "existing draft Call tool: fetch_url with parameters: ",
            capturedText,
        )
        composeRule.onNodeWithText("Add to message").assertDoesNotExist()
    }

    @Test
    fun `plus directly attaches when no tools are available`() {
        var attachInvoked = false
        setComposer(
            ComposerScenario(
                availableTools = emptyList(),
                onAttachImage = { attachInvoked = true },
            ),
        )

        openComposerActions()

        assertTrue(attachInvoked)
        composeRule.onNodeWithText("Add to message").assertDoesNotExist()
    }

    @Test
    fun `attach action invokes attachment callback and closes the sheet`() {
        var attachInvoked = false
        setComposer(ComposerScenario(onAttachImage = { attachInvoked = true }))

        openComposerActions()
        composeRule.onNodeWithText("Attach image").performClick()

        assertTrue(attachInvoked)
        composeRule.onNodeWithText("Add to message").assertDoesNotExist()
    }

    private fun setComposer(scenario: ComposerScenario = ComposerScenario()) {
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = scenario.inputText,
                    pendingAttachments = scenario.pendingAttachments,
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = scenario.onTextChange,
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = scenario.onAttachImage,
                    availableTools = scenario.availableTools ?: listOf(tool),
                )
            }
        }
    }

    private fun openComposerActions() {
        composeRule
            .onNodeWithContentDescription("Open composer actions")
            .performClick()
    }
}
