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
import com.letta.mobile.ui.components.LocalToolCardBodyFill
import com.letta.mobile.data.model.UiSubagentNotification
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.components.LETTA_TOOL_CARD_LINE_TAG
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
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

    private val openRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            created.copy(id = "in-run", content = "", runId = RUN_ID),
            UiMessage(id = "after", role = "assistant", content = "It is reviewing them now.", timestamp = T0, runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun ComposeUiTest.show(message: UiMessage, callbacks: ChatRowCallbacks = rowCallbacks(resolveAgentName = { "Scout" })) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme { RenderRow(single(message), rowContext(), callbacks) }
            }
        }
        waitForIdle()
    }

    private val report = UiMessage(
        id = "report", role = "assistant", content = "", timestamp = T0,
        subagentNotification = UiSubagentNotification(
            status = "completed",
            summary = "Render and compare the demo clips",
            result = REPORT_RESULT,
            usage = "tool_uses: 9\nduration_ms: 61000",
            transcriptUri = "/tmp/demo/task_1.log",
            taskId = "task_1",
            subagentAgentId = "codex_demo",
        ),
    )

    /** Every card is a quiet line: its words, then the chevron right after them; opening it is the line's own toggle. */
    private fun ComposeUiTest.openTheLine(tag: String) {
        onNode(hasTestTag(LETTA_TOOL_CARD_LINE_TAG) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        waitForIdle()
    }

    @Test
    fun aSentMessageIsALineThatOpensToTheMessageAndItsDelivery() = runComposeUiTest {
        show(sent)
        onNodeWithText("Sent message to", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.SENT_MESSAGE_TEXT, useUnmergedTree = true).assertDoesNotExist()
        openTheLine(ChatRowTestTags.SENT_MESSAGE_CARD)
        onNodeWithTag(ChatRowTestTags.SENT_MESSAGE_TEXT, useUnmergedTree = true).assertTextEquals(BODY)
        onNodeWithText("agent-scout", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aCreatedSubagentIsALineWhoseBodyOpensTheSubagent() = runComposeUiTest {
        var opened: ChatSubagentTarget? = null
        show(created, rowCallbacks(openSubagent = { opened = it }))
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_STATUS, useUnmergedTree = true).assertExists()
        openTheLine(ChatRowTestTags.CREATED_SUBAGENT_CARD)
        onNodeWithTag(ChatRowTestTags.CREATED_SUBAGENT_OPEN)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        runOnIdle { assertEquals(ChatSubagentTarget("agent-1", TASK, "agent-sub"), opened) }
    }

    @Test
    fun aLoadedSkillIsAQuietLineThatOpensItsDirectoryDescriptionAndContents() = runComposeUiTest {
        show(skill)
        onNodeWithText("Loaded skill", useUnmergedTree = true).assertExists()
        onNodeWithText("/skills/writing-guide", useUnmergedTree = true).assertDoesNotExist()
        openTheLine(ChatRowTestTags.SKILL_LOAD_CARD)
        onNodeWithText("/skills/writing-guide", useUnmergedTree = true).assertExists()
        onNodeWithText("House style for release notes.", useUnmergedTree = true).assertExists()
        awaitText("Keep sentences short.")
        onNodeWithText("Launching skill", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aSkillTheArgumentsDoNotNameTakesItsNameFromItsResult() = runComposeUiTest {
        show(skill.copy(toolCalls = skill.toolCalls.orEmpty().map { it.copy(arguments = "{}") }))
        onNodeWithText("writing-guide", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aSubagentReportShowsNothingRawUntilItsDetailIsOpened() = runComposeUiTest {
        show(report, rowCallbacks(openSubagent = {}))
        onNodeWithText("Render and compare the demo clips", useUnmergedTree = true).assertExists()
        onNodeWithText("completed", useUnmergedTree = true).assertExists()
        openTheLine(ChatRowTestTags.SUBAGENT_REPORT_CARD)
        awaitText("All four clips rendered.")
        onNodeWithText("9 tools", useUnmergedTree = true).assertExists()
        onNodeWithText("1m 1s", useUnmergedTree = true).assertExists()
        onNodeWithText("View conversation", useUnmergedTree = true).assertExists()
        listOf("tool_uses", "subagent_id=", "runtime_session_id", "codex_demo", "task_1").forEach { raw ->
            onNodeWithText(raw, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        onNodeWithTag(ChatRowTestTags.SUBAGENT_REPORT_DETAIL).performClick()
        waitForIdle()
        onNodeWithText("sess-1", useUnmergedTree = true).assertExists()
        onNodeWithText("/tmp/demo/task_1.log", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theCardsRenderAtEveryReviewWidth() {
        for (dark in listOf(true, false)) {
            for (width in REVIEW_WIDTHS) {
                render(width, dark, fill = false)
                render(width, dark, fill = true)
            }
        }
    }

    /** Every card opened, the body on no fill (the default) or on the subtle tonal fill. */
    private fun render(width: Int, dark: Boolean, fill: Boolean) = runDesktopComposeUiTest(width = width, height = FRAME_HEIGHT) {
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalLettaHost provides if (touch) LettaHost.Touch else LettaHost.Pointer,
                    LocalToolCardBodyFill provides fill,
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Column {
                                RenderRow(single(sent), rowContext(), rowCallbacks(resolveAgentName = { "Scout" }))
                                RenderRow(single(skill), rowContext(), rowCallbacks())
                                // The created subagent as an open run's step, and the report it sent back.
                                val opened = renderState(collapsedRunIds = setOf(RUN_ID))
                                RenderRow(openRun, rowContext(itemState = opened, newestMessageId = "after"))
                                RenderRow(single(report), rowContext(), rowCallbacks(openSubagent = {}))
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val lines = onAllNodesWithTag(LETTA_TOOL_CARD_LINE_TAG, useUnmergedTree = true)
        repeat(lines.fetchSemanticsNodes().size) { lines[it].performClick() }
        waitForIdle()
        awaitText("Keep sentences short.")
        awaitText("All four clips rendered.")
        val name = "cards-$width-${if (dark) "dark" else "light"}-${if (fill) "fill" else "nofill"}.png"
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/agent-tool-cards").apply { mkdirs() }.resolve(name))
    }

    private companion object {
        const val T0 = "2026-11-17T09:00:00Z"
        const val RUN_ID = "run-cards"
        const val TASK = "Review this week's open pull requests"
        const val BODY = "Please recheck the nightly build logs for the flaky upload step and tell me which runs failed and why."
        const val WIDE = 1000
        const val PHONE = 360
        const val FRAME_HEIGHT = 1600
        val REPORT_RESULT = listOf(
            "subagent_type=codex subagent_id=sub-1 subagent_status=success agent_id=codex_demo runtime_session_id=sess-1",
            "",
            "All four clips rendered. **Pick: clip B**, the steadiest lettering.",
        ).joinToString("\n")
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
