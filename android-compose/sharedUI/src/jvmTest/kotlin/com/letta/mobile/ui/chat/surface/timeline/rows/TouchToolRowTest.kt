@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ToolDisplayRegistry
import com.letta.mobile.ui.chat.render.ToolEmojis
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-bglj6.1.23: a tool row on a phone reads as the legacy status row. */
class TouchToolRowTest {
    @Composable
    private fun TouchCard(call: UiToolCall) {
        MaterialTheme {
            CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Touch) {
                ToolCard(call, disclosureKey = "call-1", callbacks = rowCallbacks())
            }
        }
    }

    private fun call(status: String?, result: String?) = UiToolCall(
        name = "Bash",
        arguments = """{"command":"ls"}""",
        result = result,
        status = status,
        toolCallId = "call-1",
    )

    @Test
    fun aSettledCallShowsItsEmojiAndASuccessGlyph() = runComposeUiTest {
        setContent { TouchCard(call(status = "success", result = "a.txt")) }
        onNodeWithTag(ChatRowTestTags.TOOL_EMOJI, useUnmergedTree = true).assertTextEquals("⚡")
        onNodeWithContentDescription("Succeeded").assertExists()
        // Open, it heads the output with its outcome.
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_OUTCOME).assertExists()
    }

    @Test
    fun aRunningCallTurnsItsGlyphAndSaysItIsExecuting() = runComposeUiTest {
        setContent { TouchCard(call(status = null, result = null)) }
        onNodeWithContentDescription("Running").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_EXECUTING).assertTextEquals("Executing Bash…")
    }

    @Test
    fun aFailedCallShowsTheErrorGlyphAndOutcome() = runComposeUiTest {
        setContent { TouchCard(call(status = "error", result = "boom")) }
        onNodeWithTag(ChatRowTestTags.TOOL_STATUS_GLYPH, useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_OUTCOME).assertExists()
    }

    @Test
    fun theSharedEmojiTableMatchesTheLegacyRegistry() {
        listOf("Bash", "Read", "Grep", "memory_insert", "web_search", "something_else").forEach { tool ->
            assertEquals(ToolDisplayRegistry.resolve(tool).emoji, ToolEmojis.forTool(tool))
        }
    }
}
