@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.canvas.CANVAS_COMPACT_TOOLBAR_TAG
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.PhoneScene
import com.letta.mobile.ui.devfixtures.PhoneSceneSurface
import com.letta.mobile.ui.devfixtures.PhoneScenes
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.markdown.SharedRichMarkdownRenderer
import com.letta.mobile.ui.theme.ChatHeadDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * The Touch canvas's reply popup, on the phone screen the snapshots draw (412 x 915 dp, the real
 * board with its tool bar): a compact card over the head that peeks at a few lines of the reply,
 * opens the chat on a tap, goes on its dismiss or a swipe, keeps clear of the board's tool bar and
 * the input tray, keeps its own type under a host's rich renderer, and snaps under reduced motion.
 */
class TouchReplyPopupUiTest {
    private class Shown(val intents: MutableList<ChatSurfaceIntent>)

    private fun ComposeUiTest.show(
        scene: PhoneScene,
        reducedMotion: Boolean = false,
        rich: SharedRichMarkdownRenderer? = null,
        port: FixtureChatSessionPort = FixtureChatSessionPort(scene.state, scene.composer),
    ): Shown {
        val intents = mutableListOf<ChatSurfaceIntent>()
        // The board keeps frame loops running: step the clock rather than wait for idle.
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalSharedRichMarkdownRenderer provides rich) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PhoneSceneSurface(scene = scene, port = port, presentation = scene.presentation, onIntent = { intents += it })
                }
            }
        }
        settle()
        return Shown(intents)
    }

    private fun ComposeUiTest.settle() {
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
    }

    @Test
    fun aLongReplyPeeksAFewLinesInACappedCard() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(longScene)
        val text = onNodeWithTag(TOUCH_POPUP_TEXT_TAG, useUnmergedTree = true).getBoundsInRoot()
        val card = onNodeWithTag(TOUCH_POPUP_TAG).getBoundsInRoot()
        // bodyMedium's 20sp line at density 1 and font scale 1.
        val peek = BODY_MEDIUM_LINE_DP * ChatHeadDimens.popupPeekLines
        assertTrue((text.bottom - text.top).value <= peek + TOLERANCE_DP, "the reply is not capped at its peek: $text")
        assertTrue((card.right - card.left) <= ChatHeadDimens.popupMaxWidth + TOLERANCE_DP.dp, "the card is too wide: $card")
        assertTrue((card.bottom - card.top).value <= peek + CARD_CHROME_DP, "the card is too tall: $card")
    }

    @Test
    fun aShortReplyMakesASmallCardOverTheHead() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(shortScene)
        val card = onNodeWithTag(TOUCH_POPUP_TAG).getBoundsInRoot()
        val head = onNodeWithTag(TOUCH_HEAD_TAG).getBoundsInRoot()
        assertTrue((card.right - card.left) < ChatHeadDimens.popupMaxWidth, "a two-word reply took the full width: $card")
        assertTrue(card.bottom <= head.top, "the card is not above the head: $card vs $head")
        assertEquals(head.right.value, card.right.value, TOLERANCE_DP, "the card does not speak from the head's corner")
    }

    @Test
    fun theDismissHidesThePopup() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(shortScene)
        onNodeWithTag(TOUCH_POPUP_DISMISS_TAG).performClick()
        settle()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
    }

    @Test
    fun aSidewaysSwipeDismissesThePopup() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(longScene)
        onNodeWithTag(TOUCH_POPUP_TAG).performTouchInput { swipeRight(startX = centerX, endX = right + width) }
        settle()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
    }

    @Test
    fun aTapOpensTheBubblesCard() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val shown = show(longScene)
        onNodeWithTag(TOUCH_POPUP_TEXT_TAG, useUnmergedTree = true).performClick()
        settle()
        // letta-mobile-y5q9z: on the canvas the reply opens the bubble's card, not the full chat.
        onNodeWithTag(BUBBLE_CARD_TAG).assertExists()
        assertTrue(shown.intents.isEmpty(), "the reply opened the full chat: ${shown.intents}")
    }

    @Test
    fun theCardKeepsClearOfTheBoardsToolBar() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(longScene)
        assertClearOfTheToolBar()
    }

    @Test
    fun onTheLeftTheCardKeepsClearOfTheBoardsToolBar() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(longScene.copy(dock = ChatDockGeometry(anchorX = 0f, anchorY = 1f)))
        assertClearOfTheToolBar()
        val card = onNodeWithTag(TOUCH_POPUP_TAG).getBoundsInRoot()
        val head = onNodeWithTag(TOUCH_HEAD_TAG).getBoundsInRoot()
        assertEquals(head.left.value, card.left.value, TOLERANCE_DP, "the card does not speak from the head's corner")
    }

    private fun ComposeUiTest.assertClearOfTheToolBar() {
        val card = onNodeWithTag(TOUCH_POPUP_TAG).getBoundsInRoot()
        val toolbar = onNodeWithTag(CANVAS_COMPACT_TOOLBAR_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(card.bottom <= toolbar.top, "the card runs into the tool bar: $card vs $toolbar")
    }

    @Test
    fun highOnTheScreenTheCardOpensBelowTheHeadAndStillClearsTheToolBar() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(longScene.copy(dock = ChatDockGeometry(anchorX = 1f, anchorY = 0f)))
        val card = onNodeWithTag(TOUCH_POPUP_TAG).getBoundsInRoot()
        val head = onNodeWithTag(TOUCH_HEAD_TAG).getBoundsInRoot()
        val toolbar = onNodeWithTag(CANVAS_COMPACT_TOOLBAR_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(card.top >= head.bottom, "the card is not below the head: $card vs $head")
        assertTrue(card.bottom <= toolbar.top, "the card runs into the tool bar: $card vs $toolbar")
    }

    @Test
    fun aWaitingQuestionTakesThePopupsPlace() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(shortScene.copy(state = withQuestion(shortScene.state)))
        onNodeWithTag(TOUCH_INPUT_TRAY_TAG).assertExists()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
    }

    @Test
    fun aHostsRichRendererDoesNotPaintThePopup() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val painted = mutableListOf<String>()
        show(shortScene, rich = SharedRichMarkdownRenderer { text, _, _, _ -> painted += text })
        onNodeWithTag(TOUCH_POPUP_TEXT_TAG, useUnmergedTree = true).assertExists()
        assertTrue(painted.isEmpty(), "the popup took the host renderer's large type: $painted")
    }

    @Test
    fun underReducedMotionThePopupComesAndGoesAtOnce() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(shortScene, reducedMotion = true)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        mainClock.advanceTimeBy(FEW_FRAMES_MILLIS)
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        mainClock.advanceTimeBy(FEW_FRAMES_MILLIS)
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(1)
    }

    @Test
    fun withMotionThePopupFoldsBackIntoTheHead() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        show(shortScene)
        onNodeWithTag(TOUCH_HEAD_TAG).performClick()
        mainClock.advanceTimeBy(FEW_FRAMES_MILLIS)
        // Still on its way out a frame in...
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(1)
        settle()
        onAllNodesWithTag(TOUCH_POPUP_TAG).assertCountEquals(0)
    }

    private fun withQuestion(state: ChatUiState): ChatUiState {
        val question = UiMessage(
            id = "q1",
            role = "assistant",
            content = "",
            timestamp = "2026-10-01T10:20:10Z",
            approvalRequest = UiApprovalRequest(
                requestId = "req-1",
                toolCalls = listOf(
                    UiApprovalToolCall(
                        toolCallId = "ask-1",
                        name = "AskUserQuestion",
                        arguments = """{"questions":[{"question":"Which host first?","options":[{"label":"Web"},{"label":"DB"}]}]}""",
                    ),
                ),
            ),
        )
        return state.copy(messages = (state.messages + question).toImmutableList())
    }

    private companion object {
        const val WIDTH = PhoneFixtures.PHONE_WIDTH_DP
        const val HEIGHT = PhoneFixtures.PHONE_HEIGHT_DP
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L

        /** A few frames: enough for a snap to land, well short of the pop's exit ramp. */
        const val FEW_FRAMES_MILLIS = 48L
        const val TOLERANCE_DP = 1f
        const val BODY_MEDIUM_LINE_DP = 20f

        /** The card's padding above and below its text. */
        const val CARD_CHROME_DP = 26f

        val shortScene: PhoneScene = PhoneScenes.canvasReplyPopup.copy(
            id = "touch-reply-popup-short",
            dock = ChatDockGeometry(anchorX = 1f, anchorY = 1f),
            state = PhoneFixtures.state.copy(
                messages = persistentListOf(PhoneFixtures.messages[2], PhoneFixtures.messages[3].copy(content = "All done.")),
            ),
        )

        val longScene: PhoneScene = shortScene.copy(
            id = "touch-reply-popup-long",
            state = PhoneFixtures.state.copy(
                messages = persistentListOf(
                    PhoneFixtures.messages[2],
                    PhoneFixtures.messages[3].copy(
                        content = "Done on my end. HSTS is live and the backup is in place. I also rotated the " +
                            "certificates, checked the renewal timer, wrote the rollback steps to the board and " +
                            "left a note on the two hosts that still need a restart tonight.",
                    ),
                ),
            ),
        )
    }
}
