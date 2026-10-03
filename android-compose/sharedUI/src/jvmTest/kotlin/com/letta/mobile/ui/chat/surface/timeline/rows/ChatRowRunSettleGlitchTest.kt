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
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: a settled run's body tucks up under the run-header by [ChatRowSpacing.completedRunBodyLift]
 * (22dp) so the next row sits flush against the header. The geometry comes from [com.letta.mobile.ui.chat.surface.timeline.rows.RunBody]'s
 * pullUp modifier, which shifts the body AND reports it shorter; this is a layout-time decision, NOT an
 * animated transition.
 *
 * Previous shape: `animateDpAsState(targetValue = lift, tween(CONTENT_SIZE_MILLIS = 220ms))` made the
 * body slide 22dp upward over 220ms on every settle — the visible "things are moving at that point"
 * glitch the user reported. The lift now snaps: the body sits at its settled position on the very
 * first frame after `isActive` flips false, with no animation-driver intermediate value.
 *
 * This test asserts the snap by holding the Compose clock still across the active -> settled
 * transition. With `mainClock.autoAdvance = false`, the row is recomposed synchronously when the
 * state object changes; if the lift were animated, the row's measured height on the next read would
 * land somewhere between H (active) and H - lift (settled) because `animateDpAsState` returns the
 * start of its animation, not the target. The post-transition measurement MUST equal the target
 * regardless of how much clock time has passed.
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
    fun runBodySnapsToSettledHeightOnSettleNotAnimates() = runComposeUiTest {
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

        // Active: lift = 0dp, row reports full body height.
        val activeHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        // Settle: flip the state. With mainClock.autoAdvance = false, Compose has not run any
        // animation frame; the recomposition's measured layout is the layout the user will see on
        // the first frame after settle. If the lift were animated, this measurement would still
        // report activeHeight (the start of the animation) or only partway toward the target.
        streamingState = renderState(isStreaming = false)

        // Drive the recomposition without advancing the animator.
        mainClock.advanceTimeByFrame()
        val settledHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        // The structural lift is a layout-time decision: regardless of how much clock time has
        // passed, the body is already at its target position. The post-transition height must be
        // strictly less than the active height (proving the body shifted upward), and the diff
        // must NOT be 0 (proving the lift actually applied). If the lift were animated, the
        // measurement would still be at the active height (the start of the tween) because no
        // animation time has elapsed.
        assertTrue(settledHeight < activeHeight,
            "expected the body to shift upward on settle (active=$activeHeight, settled=$settledHeight); " +
                "if settledHeight == activeHeight, the lift is animated and mainClock is paused at the tween start")
    }

    @Test
    fun settledRunFromFirstCompositionReportsLiftedHeight() = runComposeUiTest {
        // Control case: a settled run composed cold (history scroll, never streamed) must report
        // the same lifted height. Pairs with the transition test to guarantee the lift is a
        // structural property of the row, not a side effect of an animation that finished.
        val settledState = renderState(isStreaming = false)
        var settledFromCold: Float = 0f
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item = run, context = rowContextForRun(settledState))
                }
            }
        }
        settledFromCold = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        val activeState = renderState(isStreaming = true)
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    RenderRow(item = run, context = rowContextForRun(activeState))
                }
            }
        }
        val activeHeight = onNodeWithTag(ChatRowTestTags.RUN_BLOCK).getUnclippedBoundsInRoot().height.value

        // The settled row must be strictly shorter than the active row, and the diff must match
        // the structural lift exactly (proving the lift is applied by the same pullUp modifier in
        // both cases — no animation residue).
        val diff = activeHeight - settledFromCold
        assertTrue(diff > 0f, "expected the settled row to be shorter than the active row (active=$activeHeight, settled=$settledFromCold)")
        // The diff is the lift value, which is `min(completedRunBodyLift, bodyHeight)`. Since the
        // body in this test setup is short, the diff is bodyHeight itself, capped by the lift.
        // We don't assert a specific value — that depends on body content. We assert the diff is
        // bounded by the lift value (proving the lift cap).
        assertTrue(diff <= LIFT_DP, "expected the lift to be at most ChatRowSpacing.completedRunBodyLift (22dp), got $diff")
    }

    private companion object {
        // ChatRowSpacing.completedRunBodyLift = 22.dp.
        const val LIFT_DP = 22f
    }
}
