@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.timeline.timelineLeadingSpace
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatRowSpacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1.23: the Touch rows' parity with the legacy Android timeline. */
class TouchRowPolishTest {
    private val image = UiImageAttachment(base64 = "", mediaType = "image/png")

    @Test
    fun aPromptTheNextBubbleContinuesTightensAndOnlyTheFirstIsLabelled() {
        assertEquals(PromptGrouping(leads = true, continues = false), PromptGrouping.of(GroupPosition.None))
        assertEquals(PromptGrouping(leads = true, continues = true), PromptGrouping.of(GroupPosition.First))
        assertEquals(PromptGrouping(leads = false, continues = true), PromptGrouping.of(GroupPosition.Middle))
        assertEquals(PromptGrouping(leads = false, continues = false), PromptGrouping.of(GroupPosition.Last))
    }

    @Test
    fun structuredRowsLeadTheirGroupWithASpeakerHeader() {
        val withImages = message("a-1", "assistant", "").copy(attachments = listOf(image))
        assertTrue(showsSpeakerHeader(withImages, GroupPosition.None))
        assertTrue(showsSpeakerHeader(withImages, GroupPosition.First))
        assertFalse(showsSpeakerHeader(withImages, GroupPosition.Middle))
        assertTrue(showsSpeakerHeader(message("t-1", "tool", "ok"), GroupPosition.None))
        // Prose and a bare tool line stay bubble-less; an error labels itself.
        assertFalse(showsSpeakerHeader(message("a-2", "assistant", "hello"), GroupPosition.None))
        val toolLine = message("a-3", "assistant", "").copy(toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = null, toolCallId = "c")))
        assertFalse(showsSpeakerHeader(toolLine, GroupPosition.None))
        assertFalse(showsSpeakerHeader(withImages.copy(isError = true), GroupPosition.None))
    }

    /** letta-mobile-bglj6.1.25: only an approval that waits on the person makes the row a card. */
    @Test
    fun onlyAnApprovalWaitingOnThePersonEarnsASpeakerHeader() {
        val call = UiToolCall(name = "Bash", arguments = "{}", result = null, toolCallId = "c")
        fun requesting(tool: String) = message("a-4", "assistant", "").copy(
            toolCalls = listOf(call),
            approvalRequest = UiApprovalRequest("req-1", listOf(UiApprovalToolCall("c", tool, "{}"))),
        )
        assertFalse(showsSpeakerHeader(requesting("Bash"), GroupPosition.None))
        assertTrue(showsSpeakerHeader(requesting("AskUserQuestion"), GroupPosition.None))
    }

    @Test
    fun aTouchRowShowsTheAgentLabelAboveItsImages() = runComposeUiTest {
        val msg = message("a-1", "assistant", "").copy(attachments = listOf(image))
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Touch) {
                    RenderRow(ChatRenderItem.Single(msg, GroupPosition.First))
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.SPEAKER_HEADER).assertTextEquals("Agent")
    }

    @Test
    fun aRunOfOnlyToolCallsTakesTheTightBeat() {
        val tool = message("a-1", "assistant", "").copy(toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = null, toolCallId = "c")))
        val prose = message("a-2", "assistant", "done")
        val toolsOnly = ChatRenderItem.RunBlock(runId = "r", messages = listOf(tool to GroupPosition.First, tool.copy(id = "a-3") to GroupPosition.Last))
        val mixed = ChatRenderItem.RunBlock(runId = "r", messages = listOf(tool to GroupPosition.First, prose to GroupPosition.Last))
        assertEquals(ChatRowSpacing.grouped, timelineLeadingSpace(toolsOnly))
        assertEquals(ChatRowSpacing.ungrouped, timelineLeadingSpace(mixed))
    }
}
