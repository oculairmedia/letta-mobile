@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-zxk2n.37: the run's summary is ONE line (glyph, "Worked · … · 2 tools", one chevron).
 * Folded (the default) no step row shows under it, so there is no second "Ran 2 commands" line with a
 * chevron of its own; opened, the steps list under it; a pending approval still holds the run open
 * (letta-mobile-sieej); a reconcile never moves it; and under reduced motion opening snaps.
 */
class ChatRowRunBlockSummaryUiTest {
    private fun call(id: String, status: String? = "success") =
        UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = if (status == null) null else "ok", status = status, toolCallId = id)

    private fun runOf(vararg messages: UiMessage) = ChatRenderItem.RunBlock(runId = RUN_ID, messages = messages.map { it to GroupPosition.None })

    private val run = runOf(
        UiMessage(id = "r", role = "assistant", content = "Thinking it through.", timestamp = "2026-10-11T16:00:00Z", runId = RUN_ID, isReasoning = true),
        UiMessage(id = "t", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = RUN_ID, toolCalls = listOf(call("c1"), call("c2"))),
        UiMessage(id = "a", role = "assistant", content = "Fixed it.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID),
    )

    /** How a test draws the run: the host idiom and whether motion is reduced. */
    private data class Environment(val reducedMotion: Boolean = false, val host: LettaHost = LettaHost.Touch)

    private fun ComposeUiTest.show(
        item: ChatRenderItem, opened: Boolean, actions: RecordingChatActions = RecordingChatActions(),
        environment: Environment = Environment(),
    ) {
        val state = renderState(collapsedRunIds = if (opened) setOf(RUN_ID) else emptySet())
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides environment.reducedMotion, LocalLettaHost provides environment.host) {
                MaterialTheme {
                    Box(Modifier.width(400.dp).testTag(ROW)) {
                        RenderRow(item, rowContext(itemState = state, newestMessageId = "a"), rowCallbacks(actions))
                    }
                }
            }
        }
    }

    private fun imageRun() = runOf(
        UiMessage(
            id = "read", role = "assistant", content = "", timestamp = "2026-10-11T16:00:02Z", runId = RUN_ID,
            toolCalls = listOf(UiToolCall(
                name = "Read", arguments = """{"file_path":"/tmp/pixel.png"}""", result = "[Image: pixel.png]",
                status = "success", toolCallId = "read-image",
                generatedImageAttachments = listOf(com.letta.mobile.data.model.UiImageAttachment(
                    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg==",
                    "image/png",
                )),
            )),
        ),
        UiMessage(id = "a", role = "assistant", content = "Read done.", timestamp = "2026-10-11T16:00:09Z", runId = RUN_ID),
    )

    private fun ComposeUiTest.assertOneReadImage() {
        awaitText("Read done.")
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithContentDescription("Attached image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag(ChatRowTestTags.IMAGE_GRID, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, onAllNodesWithContentDescription("Attached image", useUnmergedTree = true).fetchSemanticsNodes().size,
            "Read output is conversation content, not hidden work: exactly one image must remain visible")
    }

    @Test
    fun aFoldedReadRunStillRendersExactlyOneImage() = runComposeUiTest {
        show(imageRun(), opened = false, environment = Environment(reducedMotion = true))
        assertOneReadImage()
    }

    @Test
    fun anOpenedReadRunRendersExactlyOneImage() = runComposeUiTest {
        show(imageRun(), opened = true, environment = Environment(reducedMotion = true))
        assertOneReadImage()
    }

    @Test
    fun aFoldedPointerReadRunStillRendersExactlyOneImage() = runComposeUiTest {
        show(imageRun(), opened = false, environment = Environment(reducedMotion = true, host = LettaHost.Pointer))
        assertOneReadImage()
    }

    @Test
    fun anOpenedPointerReadRunRendersExactlyOneImage() = runComposeUiTest {
        show(imageRun(), opened = true, environment = Environment(reducedMotion = true, host = LettaHost.Pointer))
        assertOneReadImage()
    }

    @Test
    fun aFoldedRunBlockIsOneSummaryLineWithOneChevronAndNoStepRows() = runComposeUiTest {
        val actions = RecordingChatActions()
        show(run, opened = false, actions)
        awaitText("Fixed it.")

        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assert(hasClickAction())
        onNodeWithText("Worked", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("2 steps", substring = true, useUnmergedTree = true).assertExists()
        assertEquals(1, onAllNodesWithTag(ChatRowTestTags.RUN_GLYPH, useUnmergedTree = true).fetchSemanticsNodes().size)
        // No step rows, so no second line and no second chevron.
        assertEquals(0, onAllNodesWithTag(ChatRowTestTags.RUN_WORK_STEP).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size)
        // The run's answer still reads under the line.
        onNodeWithText("Fixed it.", substring = true).assertExists()
        // The whole line is the toggle.
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).performClick()
        runOnIdle { assertEquals(listOf(RUN_ID), actions.toggledRuns) }
    }

    @Test
    fun anOpenedRunBlockListsItsStepsUnderTheLine() = runComposeUiTest {
        show(run, opened = true)
        awaitText("Fixed it.")

        // ckmub.1.2: the steps list straight under the line; no "Ran 2 commands" group row.
        onNodeWithText("Ran 2 commands", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, onAllNodesWithContentDescription("command details", substring = true).fetchSemanticsNodes().size)
        val steps = onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNodes()
        assertEquals(2, steps.size)
        val header = onNodeWithTag(ChatRowTestTags.RUN_HEADER).fetchSemanticsNode().boundsInRoot
        steps.forEach { step ->
            assertTrue(step.boundsInRoot.left > header.left, "the steps are not indented under the line (${step.boundsInRoot} vs $header)")
        }
    }

    @Test
    fun aPendingApprovalRunBlockStaysOpenWithItsCard() = runComposeUiTest {
        val parked = runOf(
            UiMessage(
                id = "a",
                role = "assistant",
                content = "",
                timestamp = "2026-10-11T16:04:02Z",
                runId = RUN_ID,
                toolCalls = listOf(call("tc", status = null)),
                approvalRequest = UiApprovalRequest(
                    requestId = "req-1",
                    toolCalls = listOf(UiApprovalToolCall(toolCallId = "tc", name = "Bash", arguments = "{}")),
                    details = PendingApprovalDetails(approvalId = "req-1", toolCallId = "tc", toolName = "Bash"),
                ),
            ),
        )
        show(parked, opened = false)
        awaitText("Approval requested")

        onNodeWithText("Waiting for your approval", useUnmergedTree = true).assertExists()
        onNodeWithText("Approval requested").assertExists()
        onNodeWithText("Approve").assertExists()
        // The parked call is a step row of the open run, its glyph saying it waits.
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertTextEquals("awaiting approval")
    }

    @Test
    fun aFoldedRunBlockKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJumpOnReconcile(opened = false)
    }

    @Test
    fun anOpenedRunBlockKeepsItsHeightOnReconcile() = runComposeUiTest {
        assertNoJumpOnReconcile(opened = true)
    }

    @Test
    fun openingARunBlockSnapsUnderReducedMotion() = runComposeUiTest {
        var opened by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    Box(Modifier.width(400.dp).testTag(ROW)) {
                        val state = renderState(collapsedRunIds = if (opened) setOf(RUN_ID) else emptySet())
                        RenderRow(run, rowContext(itemState = state, newestMessageId = "a"))
                    }
                }
            }
        }
        awaitText("Fixed it.")
        val folded = awaitStableHeight()
        opened = true
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val firstFrames = height()
        val settled = awaitStableHeight()
        assertTrue(settled > folded, "opening never grew the row (folded=$folded, opened=$settled)")
        assertEquals(settled, firstFrames, "reduced motion still animated the run opening")
    }

    private fun ComposeUiTest.assertNoJumpOnReconcile(opened: Boolean) {
        var shown by mutableStateOf<ChatRenderItem>(run)
        mainClock.autoAdvance = false
        val state = renderState(collapsedRunIds = if (opened) setOf(RUN_ID) else emptySet())
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp).testTag(ROW)) {
                    RenderRow(shown, rowContext(itemState = state, newestMessageId = "a"))
                }
            }
        }
        awaitText("Fixed it.")
        val before = awaitStableHeight()
        shown = run.copy(messages = run.messages.map { (message, position) -> message.copy() to position })
        mainClock.advanceTimeByFrame()
        assertEquals(before, height(), "the run block jumped on reconcile (opened=$opened)")
    }

    private fun ComposeUiTest.height(): Float = onNodeWithTag(ROW).getUnclippedBoundsInRoot().height.value

    private fun ComposeUiTest.awaitStableHeight(): Float {
        var stable = 0
        var last = Float.NaN
        while (stable < STABLE_FRAMES) {
            mainClock.advanceTimeByFrame()
            val next = height()
            if (next == last) stable++ else stable = 0
            last = next
        }
        return last
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val ROW = "run-block-row"
        const val STABLE_FRAMES = 3
    }
}
