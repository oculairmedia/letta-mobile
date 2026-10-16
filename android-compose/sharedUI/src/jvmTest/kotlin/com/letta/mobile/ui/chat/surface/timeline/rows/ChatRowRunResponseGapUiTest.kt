@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The run's summary line belongs to the response it produced: the reply starts right under the line's
 * label (within one grouped beat, [ChatRowSpacing.grouped]), not a section break below it. Folded,
 * opened, a one-message run, and a run of prose alone all keep that, and a one-message run keeps its
 * height whether its line shows (the newest reply) or not (an older one).
 */
class ChatRowRunResponseGapUiTest {
    private val reasoningRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "r", role = "assistant", content = "Thinking it over.", timestamp = "2026-10-16T12:00:00Z", runId = RUN_ID, isReasoning = true),
            UiMessage(
                id = "t", role = "assistant", content = "", timestamp = "2026-10-16T12:00:02Z", runId = RUN_ID,
                toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = "ok", status = "success", toolCallId = "c1")),
            ),
            UiMessage(id = "a", role = "assistant", content = REPLY, timestamp = "2026-10-16T12:00:06Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    /** "Worked Â· 6.2s": a run of prose alone, so nothing folds and the line shows only while it is the newest. */
    private val proseRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = REPLY, timestamp = "2026-10-16T12:00:00Z", runId = RUN_ID),
            UiMessage(id = "b", role = "assistant", content = "And one more thing.", timestamp = "2026-10-16T12:00:06Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private val loneReply = ChatRenderItem.Single(
        UiMessage(id = "a", role = "assistant", content = REPLY, timestamp = "2026-10-16T12:00:06Z", runId = RUN_ID, latencyMs = 6_200),
        GroupPosition.None,
        stableRunId = RUN_ID,
    )

    private fun ComposeUiTest.show(item: ChatRenderItem, opened: Boolean = false, newest: String? = "a") {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    Box(Modifier.width(400.dp).testTag(ROW)) {
                        val state = renderState(collapsedRunIds = if (opened) setOf(RUN_ID) else emptySet())
                        RenderRow(item, rowContext(itemState = state, newestMessageId = newest, toolDetails = ChatToolDetails.Inline))
                    }
                }
            }
        }
        awaitText(FIRST_LINE)
        waitForIdle()
    }

    private fun ComposeUiTest.label(): Rect =
        onAllNodesWithText("Worked", substring = true, useUnmergedTree = true).fetchSemanticsNodes().ifEmpty {
            onAllNodesWithText("Thought", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        }.first().boundsInRoot

    private fun ComposeUiTest.firstReply(): Rect =
        onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun ComposeUiTest.assertReplyUnderTheLabel(what: String) {
        val label = label()
        val reply = firstReply()
        val gap = reply.top - label.bottom
        val beat = with(density) { ChatRowSpacing.grouped.toPx() }
        assertTrue(gap >= -TOLERANCE_PX, "$what: the reply overlaps the summary label (label=$label, reply=$reply)")
        assertTrue(gap <= beat + TOLERANCE_PX, "$what: the reply sits $gap px under the summary label, more than one grouped beat ($beat px)")
    }

    @Test
    fun aFoldedRunsReplyStartsRightUnderItsSummaryLine() = runComposeUiTest {
        show(reasoningRun)
        assertReplyUnderTheLabel("folded")
    }

    @Test
    fun anOpenedRunsFirstStepStartsRightUnderItsSummaryLine() = runComposeUiTest {
        show(reasoningRun, opened = true)
        val label = label()
        val step = onAllNodesWithTag(ChatRowTestTags.RUN_WORK_STEP, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val beat = with(density) { ChatRowSpacing.grouped.toPx() }
        // The step row's tag sits outside its grouped beat; its content starts that beat lower.
        val gap = step.top + beat - label.bottom
        assertTrue(gap in -TOLERANCE_PX..beat + TOLERANCE_PX, "opened: the first step sits $gap px under the summary label (beat $beat px)")
    }

    @Test
    fun aRunOfProseAloneStartsRightUnderItsSummaryLine() = runComposeUiTest {
        show(proseRun, newest = "b")
        assertReplyUnderTheLabel("prose run")
    }

    @Test
    fun aOneMessageRunsReplyStartsRightUnderItsSummaryLine() = runComposeUiTest {
        show(loneReply)
        assertReplyUnderTheLabel("one-message run")
    }

    /** The reply lands where it would with no line above it: the line takes its section break's place, so it never jumps. */
    @Test
    fun aOneMessageRunsReplyKeepsItsPlaceWhetherItsLineShowsOrNot() {
        val shown = replyTop(newest = "a")
        val hidden = replyTop(newest = null)
        assertEquals(hidden, shown, "the one-message run's reply moves as its summary line shows or folds away")
    }

    private fun replyTop(newest: String?): Float {
        var top = Float.NaN
        runComposeUiTest {
            show(loneReply, newest = newest)
            top = firstReply().top - onNodeWithTag(ROW).fetchSemanticsNode().boundsInRoot.top
        }
        return top
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val ROW = "run-response-gap-row"
        const val FIRST_LINE = "Alpha paragraph here."
        const val REPLY = "$FIRST_LINE\n\nBravo paragraph here."
        const val TOLERANCE_PX = 0.5f
    }
}
