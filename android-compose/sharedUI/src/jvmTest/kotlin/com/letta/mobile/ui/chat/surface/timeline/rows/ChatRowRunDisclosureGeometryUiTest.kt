@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.DisclosureChevronDefaults
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Run disclosure geometry follows the row's available width on either host idiom (letta-mobile-m5frz). */
class ChatRowRunDisclosureGeometryUiTest {
    private val run = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(
                id = "reasoning", role = "assistant", content = "Checking the result.",
                timestamp = "2026-10-11T16:00:00Z", runId = RUN_ID, isReasoning = true,
            ),
            UiMessage(
                id = "tool", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = RUN_ID,
                toolCalls = listOf(UiToolCall(
                    name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success", toolCallId = "call",
                )),
            ),
            UiMessage(id = "answer", role = "assistant", content = "Done.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID),
        ).map { it to GroupPosition.None },
    )

    private data class Environment(val width: Dp, val host: LettaHost)

    private fun ComposeUiTest.show(environment: Environment, item: ChatRenderItem = run) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides environment.host) {
                MaterialTheme {
                    Box(Modifier.width(environment.width)) {
                        RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), newestMessageId = "answer"))
                    }
                }
            }
        }
        awaitText("Done.")
        assertNear(environment.width, bounds(ChatRowTestTags.RUN_BLOCK).right - bounds(ChatRowTestTags.RUN_BLOCK).left)
    }

    @Test
    fun compactRunChevronsShareOneColumn() = checkWidths(listOf(360.dp, 599.dp)) { assertChevronColumn() }

    @Test
    fun wideRunChevronsShareOneColumn() = checkWidths(listOf(600.dp, 1200.dp)) { assertChevronColumn() }

    @Test
    fun compactWorkStepsUseTheFullWidth() = checkWidths(listOf(360.dp, 599.dp)) { assertWorkIndent(0.dp) }

    @Test
    fun compactOpenDetailsStartAtTheStepEdge() = checkWidths(listOf(360.dp, 599.dp)) {
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        assertNear(bounds(ChatRowTestTags.TOOL_CARD_TOGGLE).left, bounds(ChatRowTestTags.TOOL_OUTPUT).left)
    }

    @Test
    fun wideWorkAndOpenDetailsKeepTheirIndents() = checkWidths(listOf(600.dp, 1200.dp)) {
        assertWorkIndent(ChatRowSpacing.runStepIndent)
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        val detailIndent = bounds(ChatRowTestTags.TOOL_OUTPUT).left - bounds(ChatRowTestTags.TOOL_CARD_TOGGLE).left
        assertNear(ChatRowSpacing.runStepDetailIndent, detailIndent)
    }

    @Test
    fun openDetailsInAMessageWithProseFollowTheRunWidth() {
        val inline = ChatRenderItem.RunBlock(
            runId = RUN_ID,
            messages = listOf(
                UiMessage(
                    id = "answer", role = "assistant", content = "Done.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID,
                    toolCalls = listOf(UiToolCall(
                        name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success", toolCallId = "inline-call",
                    )),
                ) to GroupPosition.None,
            ),
        )
        for (width in listOf(599.dp, 600.dp)) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) {
                runDesktopComposeUiTest(width = 1400, height = 900) {
                    show(Environment(width, host), inline)
                    onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
                    val expected = if (width < 600.dp) 0.dp else ChatRowSpacing.runStepDetailIndent
                    val indent = bounds(ChatRowTestTags.TOOL_OUTPUT).left - bounds(ChatRowTestTags.TOOL_CARD_TOGGLE).left
                    assertNear(expected, indent)
                }
            }
        }
    }

    @Test
    fun openDetailsAndWorkStepsFollowWidthChanges() {
        for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) {
            runDesktopComposeUiTest(width = 1400, height = 900) {
                val width = mutableStateOf(599.dp)
                setContent {
                    CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides host) {
                        MaterialTheme {
                            Box(Modifier.width(width.value)) {
                                RenderRow(run, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID))))
                            }
                        }
                    }
                }
                onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
                for (next in listOf(599.dp, 600.dp, 599.dp)) {
                    runOnIdle { width.value = next }
                    waitForIdle()
                    assertWorkIndent(if (next < 600.dp) 0.dp else ChatRowSpacing.runStepIndent)
                    val expected = if (next < 600.dp) 0.dp else ChatRowSpacing.runStepDetailIndent
                    val indent = bounds(ChatRowTestTags.TOOL_OUTPUT).left - bounds(ChatRowTestTags.TOOL_CARD_TOGGLE).left
                    assertNear(expected, indent)
                    assertChevronColumn()
                }
            }
        }
    }

    private fun checkWidths(widths: List<Dp>, assertion: ComposeUiTest.() -> Unit) {
        for (width in widths) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) {
                runDesktopComposeUiTest(width = 1400, height = 900) {
                    show(Environment(width, host))
                    assertion()
                }
            }
        }
    }

    private fun ComposeUiTest.assertChevronColumn() {
        val header = chevronRight(ChatRowTestTags.RUN_HEADER)
        assertNear(header, chevronRight(ChatRowTestTags.TOOL_CARD_TOGGLE))
        assertNear(header, chevronRight(ChatRowTestTags.REASONING_TOGGLE))
    }

    private fun ComposeUiTest.assertWorkIndent(expected: Dp) {
        val header = bounds(ChatRowTestTags.RUN_HEADER)
        for (tag in listOf(ChatRowTestTags.TOOL_CARD_TOGGLE, ChatRowTestTags.REASONING_TOGGLE)) {
            assertNear(expected, bounds(tag).left - header.left)
            assertNear(header.right, bounds(tag).right)
        }
    }

    private fun ComposeUiTest.chevronRight(rowTag: String): Dp = onAllNodes(
        hasTestTag(DisclosureChevronDefaults.TestTag) and hasAnyAncestor(hasTestTag(rowTag)),
        useUnmergedTree = true,
    ).also { assertEquals(1, it.fetchSemanticsNodes().size, "one chevron in $rowTag") }[0].getUnclippedBoundsInRoot().right

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp) {
        assertTrue(abs(expected.value - actual.value) <= 0.5f, "expected $expected, actual $actual (tolerance 0.5 dp)")
    }

    private companion object {
        const val RUN_ID = "run-geometry"
    }
}
