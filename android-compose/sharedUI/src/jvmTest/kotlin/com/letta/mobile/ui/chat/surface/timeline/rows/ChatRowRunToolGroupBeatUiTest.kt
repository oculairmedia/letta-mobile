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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaSizingTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.persistentSetOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An open run's tool group sits one grouped beat from the run's prose, whichever comes first,
 * working or settled, on either host: a pointer host's hover-actions line is reserved only under
 * prose that ends what the run shows, never as dead space between the prose and the steps after
 * it. The group's label and its step rows start at the run's step inset.
 */
class ChatRowRunToolGroupBeatUiTest {
    private fun shell(index: Int, running: Boolean = false) = UiMessage(
        id = "t$index", role = "assistant", content = "", timestamp = "2026-11-12T10:00:0${index}Z", runId = RUN_ID,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"cd /root && sed -n '$index,9p' file"}""",
                result = if (running) null else "ok",
                status = if (running) null else "success",
                toolCallId = "call-$index",
            ),
        ),
    )

    private val prose = UiMessage(id = "n", role = "assistant", content = PROSE, timestamp = "2026-11-12T10:00:00Z", runId = RUN_ID)

    private fun run(messages: List<UiMessage>) = ChatRenderItem.RunBlock(RUN_ID, messages.map { it to GroupPosition.None })

    private val proseThenTools = run(listOf(prose) + (1..4).map { shell(it) })
    private val toolsThenProse = run((1..4).map { shell(it) } + prose.copy(timestamp = "2026-11-12T10:00:09Z"))
    private val workingProseThenTools = run(listOf(prose) + (1..3).map { shell(it) } + shell(4, running = true))

    private enum class Fold { Opened, Default }

    private fun ComposeUiTest.show(item: ChatRenderItem, host: LettaHost, width: Int, fold: Fold, working: Boolean = false) {
        val newest = item.messages().last().id
        val context = ChatRowContext(
            itemState = ChatRenderItemState(
                isStreaming = working,
                activeApprovalRequestId = null,
                // A settled run folds by default; the person's toggle opens it. A working run is open.
                collapsedRunIds = if (fold == Fold.Opened) persistentSetOf(RUN_ID) else persistentSetOf(),
                expandedReasoningMessageIds = persistentSetOf(),
            ),
            streamingMessageId = newest.takeIf { working },
            newestMessageId = newest,
            capabilities = ChatSurfaceCapabilities.Default,
        )
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides host) {
                MaterialTheme {
                    Box(Modifier.width(width.dp)) { RenderRow(item, context) }
                }
            }
        }
        awaitText("Rebuilding")
        waitForIdle()
    }

    private fun ChatRenderItem.messages(): List<UiMessage> = (this as ChatRenderItem.RunBlock).messages.map { it.first }

    @Test
    fun proseThenToolsSitsOneBeatApartAtEveryWidth() = everyEnvironment { host, width ->
        show(proseThenTools, host, width, Fold.Opened)
        assertProseThenToolsBeat(width)
        // Touch keeps its hidden action over the first line; a pointer host reserves no line here.
        if (host == LettaHost.Pointer) {
            val actions = onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT_ACTIONS).fetchSemanticsNodes()
            assertEquals(0, actions.size, "no actions line under followed prose @ $width")
        }
    }

    @Test
    fun aWorkingRunKeepsTheSameBeat() = everyEnvironment { host, width ->
        show(workingProseThenTools, host, width, Fold.Default, working = true)
        assertProseThenToolsBeat(width)
    }

    @Test
    fun toolsThenProseSitsOneBeatApartAndKeepsTheReplyActions() = everyEnvironment { host, width ->
        show(toolsThenProse, host, width, Fold.Opened)
        val group = bounds(ChatRowTestTags.RUN_TOOL_STEPS)
        val agent = bounds(ChatRowTestTags.AGENT_TEXT)
        assertNear(ChatRowSpacing.grouped, agent.top - group.bottom, "tools -> prose beat ($host @ $width)")
        assertGroupInset(width)
        // The prose ends the run: its actions line stays (Copy is always a button to accessibility).
        assertEquals(1, onAllNodesWithTag(ChatRowTestTags.AGENT_TEXT_ACTIONS).fetchSemanticsNodes().size)
    }

    @Test
    fun aFoldedRunKeepsItsReplyActionsLine() {
        runDesktopComposeUiTest(width = WINDOW_DP, height = WINDOW_DP) {
            show(proseThenTools, LettaHost.Pointer, 1200, Fold.Default)
            assertEquals(0, onAllNodesWithTag(ChatRowTestTags.RUN_TOOL_STEPS).fetchSemanticsNodes().size)
            val text = proseText()
            val actions = bounds(ChatRowTestTags.AGENT_TEXT_ACTIONS)
            assertTrue(actions.top >= text.bottom, "actions $actions overlap the text $text")
        }
    }

    private fun ComposeUiTest.assertProseThenToolsBeat(width: Int) {
        val text = proseText()
        val agent = bounds(ChatRowTestTags.AGENT_TEXT)
        val group = bounds(ChatRowTestTags.RUN_TOOL_STEPS)
        // One grouped beat under the prose's row, which ends its bubble inset under the words.
        assertNear(ChatRowSpacing.grouped, group.top - agent.bottom, "prose row -> tool group @ $width")
        val wordsToGroup = ChatRowSpacing.bubblePaddingVertical + ChatRowSpacing.grouped
        assertNear(wordsToGroup, group.top - text.bottom, "prose words -> tool group @ $width")
        assertGroupInset(width)
    }

    /** The prose at the run's edge; the group label and every step row at the run's step inset. */
    private fun ComposeUiTest.assertGroupInset(width: Int) {
        val block = bounds(ChatRowTestTags.RUN_BLOCK)
        val indent = if (width < LettaSizingTokens.COMPACT_WIDTH_BREAKPOINT) 0.dp else ChatRowSpacing.runStepIndent
        assertNear(block.left, proseText().left, "prose left @ $width")
        assertNear(indent, bounds(ChatRowTestTags.RUN_STEP_GROUP_LABEL).left - block.left, "group label inset @ $width")
        val steps = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes()
        assertEquals(4, steps.size)
        steps.indices.forEach { index ->
            val step = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE)[index].getUnclippedBoundsInRoot()
            assertNear(indent, step.left - block.left, "step $index inset @ $width")
        }
    }

    private fun everyEnvironment(assertion: ComposeUiTest.(LettaHost, Int) -> Unit) {
        for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) {
            for (width in WIDTHS_DP) {
                runDesktopComposeUiTest(width = WINDOW_DP, height = WINDOW_DP) { assertion(host, width) }
            }
        }
    }

    private fun ComposeUiTest.proseText(): DpRect =
        onAllNodesWithText("Rebuilding", substring = true, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(abs(expected.value - actual.value) <= 0.5f, "$what: expected $expected, actual $actual (tolerance 0.5 dp)")
    }

    private companion object {
        const val RUN_ID = "run-gap"
        const val PROSE = "Rebuilding it now. First I'm reading the saved code."
        const val WINDOW_DP = 1400
        val WIDTHS_DP = listOf(360, 700, 760, 1200)
    }
}
