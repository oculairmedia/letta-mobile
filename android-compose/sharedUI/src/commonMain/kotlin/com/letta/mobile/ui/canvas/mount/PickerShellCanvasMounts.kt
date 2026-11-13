package com.letta.mobile.ui.canvas.mount

import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.CanvasMountReach
import com.letta.mobile.data.canvas.mount.client.CanvasPick
import com.letta.mobile.data.canvas.mount.client.CanvasPickerState
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.shell.sidebar.ShellCanvasMountState
import com.letta.mobile.ui.shell.sidebar.ShellCanvasMounts
import com.letta.mobile.ui.shell.sidebar.ShellCanvasPickOutcome
import com.letta.mobile.ui.shell.sidebar.ShellCanvasPickPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The side menu's view of the window's [picker]: its state and picks in the menu's own terms. */
class PickerShellCanvasMounts(private val picker: ConversationCanvasPicker) : ShellCanvasMounts {
    override val state: Flow<ShellCanvasMountState> = picker.state.map(::menuStateOf)

    override fun pickPlan(): ShellCanvasPickPlan = when {
        picker.mountTarget() == null -> ShellCanvasPickPlan.Open
        picker.inCanvasMode() -> ShellCanvasPickPlan.Mount
        else -> ShellCanvasPickPlan.OpenAndMount
    }

    override suspend fun pick(canvas: CanvasId): ShellCanvasPickOutcome = outcomeOf(picker.pick(canvas))

    override suspend fun useOwn(): ShellCanvasPickOutcome = outcomeOf(picker.useOwnCanvas())

    private companion object {
        fun menuStateOf(state: CanvasPickerState): ShellCanvasMountState = ShellCanvasMountState(
            mountsOnPick = state.mountsOnPick,
            mounted = state.mounted,
            own = state.target?.ownCanvas,
            deviceOnly = state.reach == CanvasMountReach.Device,
            notForPane = setOfNotNull(state.target?.ownCanvas, state.onBoard),
        )

        fun outcomeOf(pick: CanvasPick): ShellCanvasPickOutcome = when (pick) {
            CanvasPick.OpenAsBefore -> ShellCanvasPickOutcome.OpenAsBefore
            is CanvasPick.Mounted -> ShellCanvasPickOutcome.Mounted
            CanvasPick.Unsaved -> ShellCanvasPickOutcome.Unsaved
            is CanvasPick.Failed -> outcomeOf(pick.problem)
        }

        fun outcomeOf(problem: CanvasMountProblem): ShellCanvasPickOutcome = when (problem) {
            CanvasMountProblem.Denied -> ShellCanvasPickOutcome.Denied
            is CanvasMountProblem.InvalidInput -> ShellCanvasPickOutcome.Invalid
            is CanvasMountProblem.Unavailable -> ShellCanvasPickOutcome.Unavailable
            CanvasMountProblem.OutcomeUnknown -> ShellCanvasPickOutcome.Unknown
        }
    }
}
