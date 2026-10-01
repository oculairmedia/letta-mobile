@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.CanvasArtifactError
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.13: the canvas.compose card on the narrating message. Each status renders,
 * "Show on canvas" raises the host with the receipt (its bounds), the action is offered to
 * assistive tech, and a run keeps the narration that carries a card.
 */
class ChatRowCanvasArtifactUiTest {
    private val published = receipt(CanvasArtifactStatus.Published)

    @Test
    fun aPublishedCardShowsWhatItHoldsAndShowOnCanvasRaisesTheHostWithTheBounds() = runComposeUiTest {
        val shown = mutableListOf<CanvasArtifactReceipt>()
        setContent {
            MaterialTheme {
                RenderRow(single(narration(published)), callbacks = rowCallbacks(host = ChatSurfaceHost(showOnCanvas = { shown += it })))
            }
        }
        onNodeWithText("Weekend plan").assertExists()
        onNodeWithText("6 items · Text, Checklist, Note, Group, Card").assertExists()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT_STATUS).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT_SHOW).assertHasClickAction().performClick()
        assertEquals(listOf(published), shown)
        assertEquals(ComposeBounds(80f, 80f, 712f, 746f), shown.single().bounds)
    }

    @Test
    fun withoutAFramingHostShowOnCanvasFallsBackToOpenCanvas() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme { RenderRow(single(narration(published)), callbacks = rowCallbacks(host = ChatSurfaceHost(openCanvas = { opened++ }))) }
        }
        onNodeWithText("Show on canvas").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withNoCanvasAtAllTheCardHasNoAction() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(narration(published))) } }
        onNodeWithText("Weekend plan").assertExists()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT_SHOW).assertDoesNotExist()
    }

    @Test
    fun showOnCanvasIsAnAccessibilityActionOnTheCard() = runComposeUiTest {
        val shown = mutableListOf<CanvasArtifactReceipt>()
        setContent {
            MaterialTheme {
                RenderRow(single(narration(published)), callbacks = rowCallbacks(host = ChatSurfaceHost(showOnCanvas = { shown += it })))
            }
        }
        val card = onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT)
        card.assert(hasContentDescription("Weekend plan, On the board"))
        val actions = card.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        runOnIdle { actions.single { it.label == "Show on canvas" }.action() }
        assertEquals(1, shown.size)
    }

    @Test
    fun aPendingCardSaysItIsAddingAndOffersNoActionYet() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(
                    single(narration(receipt(CanvasArtifactStatus.Pending, bounds = null))),
                    callbacks = rowCallbacks(host = ChatSurfaceHost(showOnCanvas = {})),
                )
            }
        }
        onNodeWithText("Adding to the board…").assertExists()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT_SHOW).assertDoesNotExist()
    }

    @Test
    fun aPendingCardHonoursReducedMotion() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    RenderRow(single(narration(receipt(CanvasArtifactStatus.Pending, bounds = null))))
                }
            }
        }
        // No indeterminate spinner (it never idles); the words carry the state.
        waitForIdle()
        onNodeWithText("Adding to the board…").assertExists()
    }

    @Test
    fun aFailedCardShowsTheBoardsReason() = runComposeUiTest {
        val failed = receipt(
            CanvasArtifactStatus.Failed,
            bounds = null,
            error = CanvasArtifactError("VALIDATION_FAILED", "'STICKY' is not a kind", problemCount = 3),
        )
        setContent { MaterialTheme { RenderRow(single(narration(failed)), callbacks = rowCallbacks(host = ChatSurfaceHost(showOnCanvas = {}))) } }
        onNodeWithText("Not added to the board").assertExists()
        onNodeWithText("'STICKY' is not a kind and 2 more problems").assertExists()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT_SHOW).assertDoesNotExist()
    }

    @Test
    fun aDryRunSaysNothingWasAdded() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(narration(receipt(CanvasArtifactStatus.DryRun)))) } }
        onNodeWithText("Preview only: nothing was added").assertExists()
    }

    @Test
    fun anUntitledArtifactReadsAsABoardUpdate() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(narration(published.copy(title = null)))) } }
        onNodeWithText("Board update").assertExists()
    }

    @Test
    fun aRunKeepsTheNarrationThatCarriesACard() = runComposeUiTest {
        val call = UiMessage(
            id = "tc", role = "assistant", content = "", timestamp = T0, runId = "run-1",
            toolCalls = listOf(UiToolCall(name = "canvas.compose", arguments = "{}", result = "{}", status = "success", toolCallId = "t1")),
        )
        val item = ChatRenderItem.RunBlock("run-1", listOf(call to GroupPosition.First, narration(published) to GroupPosition.Last))
        setContent { MaterialTheme { RenderRow(item) } }
        onNodeWithText("It's on the board.").assertExists()
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT).assertExists()
    }

    @Test
    fun aRunKeepsACardLeftOnAToolCallWithNoNarration() = runComposeUiTest {
        val call = UiMessage(
            id = "tc", role = "assistant", content = "", timestamp = T0, runId = "run-1",
            toolCalls = listOf(UiToolCall(name = "canvas.compose", arguments = "{}", result = "{}", status = "success", toolCallId = "t1")),
            artifacts = listOf(published),
        )
        val other = UiMessage(
            id = "tc2", role = "assistant", content = "", timestamp = T0, runId = "run-1",
            toolCalls = listOf(UiToolCall(name = "canvas.get_scene", arguments = "{}", result = "{}", status = "success", toolCallId = "t2")),
        )
        setContent { MaterialTheme { RenderRow(ChatRenderItem.RunBlock("run-1", listOf(call to GroupPosition.First, other to GroupPosition.Last))) } }
        onNodeWithTag(ChatRowTestTags.CANVAS_ARTIFACT).assertExists()
    }

    private fun narration(receipt: CanvasArtifactReceipt) = UiMessage(
        id = "a1", role = "assistant", content = "It's on the board.", timestamp = T0, runId = "run-1", artifacts = listOf(receipt),
    )

    private companion object {
        const val T0 = "2026-10-01T12:00:00Z"

        fun receipt(
            status: CanvasArtifactStatus,
            bounds: ComposeBounds? = ComposeBounds(80f, 80f, 712f, 746f),
            error: CanvasArtifactError? = null,
        ) = CanvasArtifactReceipt(
            artifactId = "weekend-plan",
            canvasId = "canvas-conversation-conv-123",
            revision = 42,
            status = status,
            title = "Weekend plan",
            kinds = listOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP, ComposeKind.CARD),
            itemCount = 6,
            bounds = bounds,
            error = error,
            toolCallId = "t1",
        )
    }
}
