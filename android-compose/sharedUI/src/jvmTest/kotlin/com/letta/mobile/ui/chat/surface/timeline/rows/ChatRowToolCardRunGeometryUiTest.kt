@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A tool card among an open run's steps lives in the run's body: it starts below the run's header
 * (never over the header's line), and it is a line among the steps: its words start on the step rows'
 * text column, the same as the "Ran …" beside it.
 */
class ChatRowToolCardRunGeometryUiTest {
    private val agent = UiToolCall(
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
        ),
    )

    private val bash = UiToolCall(
        name = "Bash",
        arguments = """{"command":"ls"}""",
        result = "ok",
        status = "success",
        toolCallId = "bash-1",
    )

    private fun run(vararg calls: UiToolCall) = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "work", role = "assistant", content = "", timestamp = T0, runId = RUN_ID, toolCalls = calls.toList()),
            UiMessage(id = "reply", role = "assistant", content = "It is reviewing them now.", timestamp = T1, runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private fun ComposeUiTest.showOpen(item: ChatRenderItem) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides LettaHost.Pointer) {
                MaterialTheme {
                    Box(Modifier.width(WIDTH.dp)) {
                        RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "reply"))
                    }
                }
            }
        }
        awaitText("It is reviewing them now.")
        waitForIdle()
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun aCardOpeningTheRunStartsBelowItsHeader() = runComposeUiTest {
        showOpen(run(agent))
        val header = bounds(ChatRowTestTags.RUN_HEADER)
        val card = bounds(ChatRowTestTags.CREATED_SUBAGENT_CARD)
        // A card is a line among the steps: it rises into the header's floor like them, below the line's highlight.
        val highlightBottom = header.bottom - ChatRowSpacing.runHeaderToggleFloor
        assertTrue(card.top >= highlightBottom - TOLERANCE.dp, "the card $card overlaps the run header's line $header")
        assertTrue(card.bottom <= bounds(ChatRowTestTags.RUN_BLOCK).bottom + TOLERANCE.dp, "the card stays inside the run block")
    }

    @Test
    fun aCardAmongStepsStartsOnTheirTextColumn() = runComposeUiTest {
        showOpen(run(bash, agent))
        val ran = onAllNodesWithText("Ran", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val label = onAllNodesWithText("Created subagent", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val card = bounds(ChatRowTestTags.CREATED_SUBAGENT_CARD)
        assertTrue(abs((label.left - ran.left).value) <= TOLERANCE, "the card's words start at ${label.left}, the step text at ${ran.left}")
        assertTrue(card.top >= ran.bottom, "the card sits under the step before it")
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val T0 = "2026-11-17T09:00:00Z"
        const val T1 = "2026-11-17T09:00:05Z"
        const val TASK = "Review this week's open pull requests"
        const val WIDTH = 900
        const val TOLERANCE = 1f
    }
}
