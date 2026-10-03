@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.toImmutableSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.1.12: settling a run must not move the timeline. The settle once slid the run's
 * body 22dp up under its header (an animated `completedRunBodyLift`); #1758 made that lift structural
 * so it snapped instead of sliding.
 *
 * letta-mobile-bglj6.1.11: the run header and its tuck are gone. The run's label ("Working" ->
 * "Worked for 9s") leads the tool summary's own line, so the newest run has the same shape working
 * and settled: settling only rewrites the label. These tests hold that: on the first frame after
 * `isActive` flips false (the clock held still, so no animation can have run) the row is already
 * exactly its settled height, and that height is the one a run composed cold as settled reports.
 */
class ChatRowRunSettleGlitchTest {
    private fun call(id: String) = UiToolCall(name = "shell", arguments = "ls", result = "ok", status = "success", toolCallId = id)

    private val run = ChatRenderItem.RunBlock(
        runId = "run-1",
        messages = listOf(
            UiMessage(id = "a", role = "assistant", content = "", timestamp = "2026-07-19T12:00:00Z", runId = "run-1", toolCalls = listOf(call("c1"), call("c2"))),
            UiMessage(id = "b", role = "assistant", content = "All done here.", timestamp = "2026-07-19T12:00:09Z", runId = "run-1"),
        ).map { it to GroupPosition.None },
    )

    private fun renderState(isStreaming: Boolean) = ChatRenderItemState(
        isStreaming = isStreaming,
        activeApprovalRequestId = null,
        collapsedRunIds = emptySet<String>().toImmutableSet(),
        expandedReasoningMessageIds = emptySet<String>().toImmutableSet(),
    )

    /** A row context whose streaming id matches the run's final message, so the run is "active"
     * when [renderState] is streaming. [isStreamingInState] controls the conversation-wide flag
     * independently; flipping it without changing the streaming id drives the settle transition
     * the row sees in production. */
    private fun rowContextForRun(itemState: ChatRenderItemState) = ChatRowContext(
        itemState = itemState,
        streamingMessageId = "b",
        newestMessageId = "b",
        toolDetails = ChatToolDetails.Sheet,
        capabilities = ChatSurfaceCapabilities.Default,
    )

    @Test
    fun settlingTheNewestRunDoesNotMoveIt() = runComposeUiTest {
        // The state is a mutableStateOf so flipping the value drives a recompose, not a stale
        // captured reference. The composition reads `streamingState.value` and the row sees the
        // new ChatRenderItemState without re-running setContent.
        var streamingState by mutableStateOf(renderState(isStreaming = true))
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item = run, context = rowContextForRun(streamingState))
                }
            }
        }

        val activeHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        // Settle, then draw ONE frame: nothing animated can have advanced.
        streamingState = renderState(isStreaming = false)
        mainClock.advanceTimeByFrame()
        val settledHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        assertEquals(activeHeight, settledHeight, "the run changed height on settle (active=$activeHeight, settled=$settledHeight)")
    }

    @Test
    fun aRunComposedSettledHasTheSameShapeAsAWorkingOne() = runComposeUiTest {
        // Control case: a settled run composed cold (history scroll, never streamed). Pairs with
        // the transition test: the settled shape is structural, not where an animation stopped.
        val settledState = renderState(isStreaming = false)
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item = run, context = rowContextForRun(settledState))
                }
            }
        }
        val settledFromCold = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        val activeState = renderState(isStreaming = true)
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item = run, context = rowContextForRun(activeState))
                }
            }
        }
        val activeHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        // Cold (history scroll, never streamed) or settled live, the row is one shape.
        assertEquals(activeHeight, settledFromCold, "a settled run is not the working run's shape (active=$activeHeight, settled=$settledFromCold)")
    }
}
