@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.components.LETTA_TOOL_CARD_LINE_TAG
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message sent to another agent and a created subagent draw as the shared compact card: the
 * recipient and a one-line preview its chevron opens, the task with its status, a "Detail" link to
 * the call's details, and the subagent card opening the subagent. Both controls are buttons to
 * assistive technology. The render test writes both cards at the review widths in Dark and Light to
 * build/agent-tool-cards/ for review.
 */
class ChatRowToolCardsUiTest {
    private val sent = UiMessage(
        id = "sent", role = "assistant", content = "Asking Scout to recheck the nightly logs.", timestamp = T0,
        toolCalls = listOf(
            UiToolCall(
                name = "agent_message_send",
                arguments = """{"to":"agent-scout","body":"$BODY"}""",
                result = """{"ok":true,"delivered":true,"msgId":"m-1","to":"agent-scout"}""",
                status = "success",
                toolCallId = "send-1",
                agentMessageProvenance = AgentMessageProvenance(
                    direction = AgentMessageDirection.OUTBOUND,
                    fromAgentId = "agent-main",
                    toAgentId = "agent-scout",
                    msgId = "m-1",
                    deliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
                ),
            ),
        ),
    )

    private val created = UiMessage(
        id = "created", role = "assistant", content = "A subagent is reviewing them now.", timestamp = T0,
        toolCalls = listOf(
            UiToolCall(
                name = "Agent",
                arguments = """{"description":"$TASK","subagent_type":"general-purpose","prompt":"Read the open pull requests"}""",
                result = null,
                status = "running",
                toolCallId = "agent-1",
                subagentDispatch = UiSubagentDispatch(
                    toolCallId = "agent-1",
                    description = TASK,
                    subagentType = "general-purpose",
                    runInBackground = true,
                    prompt = "Read the open pull requests",
                    subagentAgentId = "agent-sub",
                ),
            ),
        ),
    )

    private val skill = UiMessage(
        id = "skill", role = "assistant", content = "Loaded the writing guide.", timestamp = T0,
        toolCalls = listOf(
            UiToolCall(
                name = "Skill",
                arguments = """{"skill":"writing-guide"}""",
                result = SKILL_RESULT,
                status = "success",
                toolCallId = "skill-1",
            ),
        ),
    )

    private fun ComposeUiTest.show(message: UiMessage, callbacks: ChatRowCallbacks = rowCallbacks(resolveAgentName = { "Scout" })) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme { RenderRow(single(message), rowContext(), callbacks) }
            }
        }
        waitForIdle()
    }

    @Test
    fun aSentMessageShowsItsRecipientAndAOneLinePreviewItsChevronOpens() = runComposeUiTest {
        show(sent)
        onNodeWithTag(ChatRowTestTags.SENT_MESSAGE_CARD).assertExists()
        assertEquals(1, lineCount(), "the message previews as one line")
        onNodeWithContentDescription("Show full message").performClick()
        waitForIdle()
        assertTrue(lineCount() > 1, "opened, the whole message shows")
        onNodeWithContentDescription("Show message preview").performClick()
        waitForIdle()
        assertEquals(1, lineCount(), "closed again, it previews as one line")
    }

    @Test
    fun detailIsAButtonThatOpensTheCallsDetails() = runComposeUiTest {
        show(sent)
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_DETAIL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        waitForIdle()
        // Opened, Detail shows the delivery facts: the recipient's id in monospace among them.
        onNodeWithText("agent-scout", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_DETAIL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun aCreatedSubagentShowsItsTaskAndStatusAndOpensTheSubagent() = runComposeUiTest {
        var opened: ChatSubagentTarget? = null
        show(created, rowCallbacks(openSubagent = { opened = it }))
        onNodeWithTag(ChatRowTestTags.CREATED_SUBAGENT_CARD).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_STATUS, useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.CREATED_SUBAGENT_CARD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        runOnIdle { assertEquals(ChatSubagentTarget("agent-1", TASK, "agent-sub"), opened) }
    }

    @Test
    fun aLoadedSkillIsAQuietLineThatOpensItsDirectoryDescriptionAndContents() = runComposeUiTest {
        show(skill)
        onNodeWithTag(ChatRowTestTags.SKILL_LOAD_CARD).assertExists()
        onNodeWithText("Loaded skill", useUnmergedTree = true).assertExists()
        onNodeWithText("/skills/writing-guide", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(LETTA_TOOL_CARD_LINE_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        waitForIdle()
        onNodeWithText("/skills/writing-guide", useUnmergedTree = true).assertExists()
        onNodeWithText("House style for release notes.", useUnmergedTree = true).assertExists()
        awaitText("Keep sentences short.")
        onNodeWithText("Launching skill", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theCardsTakeThreeFifthsOfAWideColumnAndAllOfAPhones() {
        val (wideCard, wideColumn) = cardAndColumn(WIDE)
        assertTrue(kotlin.math.abs(wideCard - wideColumn * 3 / 5) <= 1, "a wide column's card is $wideCard of $wideColumn")
        val (phoneCard, phoneColumn) = cardAndColumn(PHONE)
        assertEquals(phoneColumn, phoneCard, "a phone column's card spans it")
    }

    /** The sent-message card's width and its prose column's, in whole dp, in a frame [width] wide. */
    private fun cardAndColumn(width: Int): Pair<Int, Int> {
        var measured = 0 to 0
        runDesktopComposeUiTest(width = width, height = FRAME_HEIGHT) {
            setContent { MaterialTheme { RenderRow(single(sent), rowContext(), rowCallbacks(resolveAgentName = { "Scout" })) } }
            waitForIdle()
            val card = onNodeWithTag(ChatRowTestTags.SENT_MESSAGE_CARD).getUnclippedBoundsInRoot()
            val column = onNodeWithTag(ChatRowTestTags.AGENT_TEXT).getUnclippedBoundsInRoot()
            measured = (card.right - card.left).value.toInt() to (column.right - column.left).value.toInt()
        }
        return measured
    }

    private fun ComposeUiTest.lineCount(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag(ChatRowTestTags.SENT_MESSAGE_TEXT, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().lineCount
    }

    @Test
    fun bothCardsRenderAtEveryReviewWidth() {
        for (dark in listOf(true, false)) {
            for (width in REVIEW_WIDTHS) render(width, dark)
        }
    }

    private fun render(width: Int, dark: Boolean) = runDesktopComposeUiTest(width = width, height = FRAME_HEIGHT) {
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLettaHost provides if (touch) LettaHost.Touch else LettaHost.Pointer) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Column {
                                RenderRow(single(sent), rowContext(), rowCallbacks(resolveAgentName = { "Scout" }))
                                RenderRow(single(created), rowContext(), rowCallbacks(openSubagent = {}))
                                RenderRow(single(skill), rowContext(), rowCallbacks())
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        // The skill opened, so its panel (directory, description, contents) is in the frame too.
        onNodeWithTag(LETTA_TOOL_CARD_LINE_TAG).performClick()
        waitForIdle()
        awaitText("Keep sentences short.")
        val file = File("build/agent-tool-cards").apply { mkdirs() }.resolve("cards-$width-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private companion object {
        const val T0 = "2026-11-17T09:00:00Z"
        const val TASK = "Review this week's open pull requests"
        const val BODY = "Please recheck the nightly build logs for the flaky upload step and tell me which runs failed and why."
        const val WIDE = 1000
        const val PHONE = 360
        const val FRAME_HEIGHT = 760
        val SKILL_RESULT = listOf(
            "Launching skill: writing-guide",
            "Base directory for this skill: /skills/writing-guide",
            "---",
            "name: writing-guide",
            "description: House style for release notes.",
            "---",
            "# Writing guide",
            "",
            "Keep sentences short. Lead with what changed for the reader.",
        ).joinToString("\n")
        val REVIEW_WIDTHS = listOf(360, 700, 760, 1200)
    }
}
