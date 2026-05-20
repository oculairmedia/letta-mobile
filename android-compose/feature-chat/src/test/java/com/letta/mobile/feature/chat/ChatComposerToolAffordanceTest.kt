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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            .onNodeWithTag("${ToolAffordanceRowTestTags.ChipPrefix}tool-1")
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
    fun `clicking a tool chip without schema inserts the flat-fallback template`() {
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
            .onNodeWithTag("${ToolAffordanceRowTestTags.ChipPrefix}tool-1")
            .performClick()
        assertEquals("Call tool: fetch_url with parameters: ", captured)
    }

    @Test
    fun `clicking a tool chip with json schema inserts a multi-line typed template`() {
        var captured = ""
        val schemaTool = Tool(
            id = ToolId("tool-3"),
            name = "send_email",
            jsonSchema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "to" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                            "cc_count" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                            "urgent" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                        ),
                    ),
                ),
            ),
        )
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
                    availableTools = listOf(schemaTool),
                )
            }
        }
        composeRule
            .onNodeWithTag("${ToolAffordanceRowTestTags.ChipPrefix}tool-3")
            .performClick()
        assertTrue("expected multi-line block, got '$captured'", captured.contains("\n"))
        assertTrue(captured.contains("Call tool: send_email"))
        assertTrue(captured.contains("Arguments: {"))
        assertTrue(captured.contains("\"to\": \"\""))
        assertTrue(captured.contains("\"cc_count\": 0"))
        assertTrue(captured.contains("\"urgent\": false"))
    }
}
