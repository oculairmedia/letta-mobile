package com.letta.mobile.ui.canvas.mount

import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.CanvasPick
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.shell.sidebar.ShellCanvasMountState
import com.letta.mobile.ui.shell.sidebar.ShellCanvasMounts
import com.letta.mobile.ui.shell.sidebar.ShellCanvasPickOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The side menu's view of the window's [picker]: its state and picks in the menu's own terms. */
class PickerShellCanvasMounts(private val picker: ConversationCanvasPicker) : ShellCanvasMounts {
    override val state: Flow<ShellCanvasMountState> = picker.state.map { ShellCanvasMountState(it.mountsOnPick, it.mounted) }

    override fun mountsOnPick(): Boolean = picker.mountTarget() != null

    override suspend fun pick(canvas: CanvasId): ShellCanvasPickOutcome = outcomeOf(picker.pick(canvas))

    override suspend fun useOwn(): ShellCanvasPickOutcome = outcomeOf(picker.useOwnCanvas())

    private companion object {
        fun outcomeOf(pick: CanvasPick): ShellCanvasPickOutcome = when (pick) {
            CanvasPick.OpenAsBefore -> ShellCanvasPickOutcome.OpenAsBefore
            is CanvasPick.Mounted -> ShellCanvasPickOutcome.Mounted
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
