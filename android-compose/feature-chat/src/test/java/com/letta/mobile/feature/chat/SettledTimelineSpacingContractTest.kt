package com.letta.mobile.feature.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.PagedChatMessageList
import com.letta.mobile.feature.chat.screen.RunActivityDisclosureTestTags
import com.letta.mobile.feature.chat.screen.ToolRunSummaryTestTags
import com.letta.mobile.feature.chat.screen.chatListBottomPadding
import com.letta.mobile.feature.chat.screen.messagelist.ChatTimelineRowTestTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaSpacingTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * letta-mobile-jqiu3 (AC1/AC2/AC3/AC4, layout half). A settled, expanded multi-segment turn is
 * rendered through the production paged timeline and measured, not eyeballed:
 *
 * - every drawn step surface carries visible content (no invisible space consumers),
 * - adjacent step surfaces are separated by exactly one spacing token, so the run's extent equals
 *   the sum of visible surface heights plus (visibleCount - 1) tokens,
 * - the region below the newest content is exactly the list's bottom reserve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h2400dp")
class SettledTimelineSpacingContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun adjacentVisibleStepsAreSeparatedByOneToken() {
        renderSettledTurn(bottomPadding = 96.dp)
        val surfaces = runStepSurfaces()
        val token = px(LettaSpacingTokens.MESSAGE_SPACING.dp)

        assertEquals("one surface per visible step", EXPECTED_VISIBLE_STEPS, surfaces.size)
        surfaces.zipWithNext().forEach { (upper, lower) ->
            assertNear("gap between steps at ${upper.bottom} and ${lower.top}", token, lower.top - upper.bottom)
        }
        val extent = surfaces.last().bottom - surfaces.first().top
        assertNear("run extent", surfaces.sumOf { it.height.toDouble() }.toFloat() + (surfaces.size - 1) * token, extent)
    }

    @Test fun everyStepSurfaceDrawsVisibleContent() {
        renderSettledTurn(bottomPadding = 96.dp)
        val text = visibleTextBounds()

        runStepSurfaces().forEach { surface ->
            assertTrue("surface $surface draws nothing", text.any { surface.contains(it.center) })
        }
        compose.onNodeWithText("Ran 4 commands").assertIsDisplayed()
    }

    @Test fun trailingRegionEqualsTheBottomReserveForAnyComposerHeight() {
        listOf(96.dp, 240.dp).forEach { reserve ->
            renderSettledTurn(bottomPadding = reserve)
            val viewportBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
            assertNear("trailing region at reserve $reserve", px(reserve), viewportBottom - runStepSurfaces().last().bottom)
        }
    }

    @Test fun dismissedA2uiStackReservesNoSpace() {
        assertEquals(96.dp, chatListBottomPadding(composerPadding = 96.dp, a2uiShown = false, a2uiStackHeight = 180.dp))
        assertEquals(276.dp, chatListBottomPadding(composerPadding = 96.dp, a2uiShown = true, a2uiStackHeight = 180.dp))
    }

    /** One composition per test; later renders only move the bottom reserve. */
    private var reserveFlow: MutableStateFlow<Dp>? = null

    private fun renderSettledTurn(bottomPadding: Dp) {
        reserveFlow?.let {
            it.value = bottomPadding
            compose.waitForIdle()
            return
        }
        val reserve = MutableStateFlow(bottomPadding).also { reserveFlow = it }
        val items = buildChatRenderModel(SettledTurnFixture.messages(), ChatDisplayMode.Interactive).renderItems
        val presentation = ChatPagingPresentation(flowOf(PagingData.from(items)), MutableStateFlow(emptyList()), {})
        compose.setContent {
            val padding by reserve.collectAsState()
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(), noCallbacks(),
                    ChatContentAppearance(chatMode = "interactive", bottomPadding = padding),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun noCallbacks() = ChatContentCallbacks(
        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
    )

    /** Step surfaces of the run, i.e. everything drawn below the run's disclosure header. */
    private fun runStepSurfaces(): List<Rect> {
        val disclosureTop = taggedBounds(RunActivityDisclosureTestTags.Header).single().top
        return (taggedBounds(ChatTimelineRowTestTags.Surface) + taggedBounds(ToolRunSummaryTestTags.Row))
            .filter { it.top > disclosureTop }
            .sortedBy { it.top }
    }

    private fun taggedBounds(tag: String): List<Rect> =
        nodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).map { it.boundsInRoot }

    private fun visibleTextBounds(): List<Rect> = nodes(
        SemanticsMatcher("draws text") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
        },
    ).map { it.boundsInRoot }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    private fun assertNear(label: String, expected: Float, actual: Float) {
        assertTrue("$label: expected $expected px, measured $actual px", abs(expected - actual) <= 1f)
    }

    private companion object {
        /** Thought, text, tool group, Thought, text, Thought, Thought, text. */
        const val EXPECTED_VISIBLE_STEPS = 8
    }
}

/**
 * The settled shape of the reported turn: prose and thoughts between tool calls, with the
 * whitespace-only segments a model emits between calls, all one completed run.
 */
private object SettledTurnFixture {
    private sealed interface Step {
        data class Prose(val id: String, val text: String) : Step
        data class Thought(val id: String, val text: String) : Step
        data class Tool(val id: String, val name: String) : Step
    }

    private val turn = listOf(
        Step.Thought("r1", "Planning"),
        Step.Prose("t1", "I'll inspect the files."),
        Step.Tool("c1", "Edit"),
        Step.Tool("c2", "Edit"),
        Step.Thought("r2", "Checking"),
        Step.Prose("w1", "\n\n"),
        Step.Tool("c3", "Bash"),
        Step.Prose("t2", "Edited both files."),
        Step.Thought("r3", "Verify"),
        Step.Prose("w2", " "),
        Step.Tool("c4", "Bash"),
        Step.Thought("r4", "Done"),
        Step.Prose("t3", "All fixed."),
    )

    fun messages(): List<UiMessage> =
        listOf(UiMessage(id = "u1", role = "user", content = "please fix the spacing", timestamp = at(0))) +
            turn.mapIndexed { index, step -> step.toMessage(at(index + 1)) }

    private fun at(second: Int) = "2026-09-25T04:%02d:%02dZ".format(second / 60, second % 60)

    private fun Step.toMessage(timestamp: String): UiMessage = when (this) {
        is Step.Prose -> assistant(id, text, timestamp)
        is Step.Thought -> assistant(id, text, timestamp).copy(isReasoning = true)
        is Step.Tool -> assistant(id, "", timestamp).copy(
            toolCalls = listOf(
                UiToolCall(
                    name = name, arguments = """{"command":"run $id"}""", result = "ok",
                    status = "success", toolCallId = "call-$id", settled = true,
                ),
            ),
        )
    }

    private fun assistant(id: String, content: String, timestamp: String) =
        UiMessage(id = id, role = "assistant", content = content, timestamp = timestamp, runId = "run-1")
}
