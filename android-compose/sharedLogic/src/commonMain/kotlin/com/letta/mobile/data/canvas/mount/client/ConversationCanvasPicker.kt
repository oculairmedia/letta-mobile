package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The conversation whose board is on screen with the chat over it (canvas mode), for the window.
 * The chat page reports it ([enter] / [leave]); the side menu reads it. Scoped like the window's
 * chat session state: one per window, never shared across windows or accounts.
 */
class CanvasModeFocus {
    private val shown = MutableStateFlow<ConversationCanvasTarget?>(null)

    /** The conversation in canvas mode, or null when no board is on screen. */
    val current: StateFlow<ConversationCanvasTarget?> = shown.asStateFlow()

    /** [target]'s board is now on screen. */
    fun enter(target: ConversationCanvasTarget) {
        shown.value = target
    }

    /** [target]'s board left the screen; a newer conversation that entered since is kept. */
    fun leave(target: ConversationCanvasTarget) {
        shown.update { if (it == target) null else it }
    }
}

/** What the side menu draws for canvases: whether a pick mounts, and what is mounted now. */
data class CanvasPickerState(
    /** The conversation in canvas mode on a host with mounts; null while a pick opens as before. */
    val target: ConversationCanvasTarget? = null,
    /** The canvas mounted for [target], or null when it shows its own board. */
    val mounted: CanvasId? = null,
) {
    /** Whether picking a canvas mounts it (else it opens as it always has). */
    val mountsOnPick: Boolean get() = target != null
}

/** What became of one pick in the side menu. */
sealed interface CanvasPick {
    /** Not in canvas mode, or the host has no mounts: the host opens the canvas as it always has. */
    data object OpenAsBefore : CanvasPick

    /** The conversation's board switched; [change] says how. */
    data class Mounted(val change: CanvasMountChange.Applied) : CanvasPick

    /** The switch did not happen, for [problem]; the board stays as it was. */
    data class Failed(val problem: CanvasMountProblem) : CanvasPick
}

/**
 * The side menu's canvas pick: in canvas mode on a host with mounts, picking a canvas mounts it for
 * the conversation on screen (and only that conversation); otherwise it opens as before. Shared by
 * every host, so the decision is made once.
 */
class ConversationCanvasPicker(
    val resolver: ConversationCanvasResolver,
    val focus: CanvasModeFocus = CanvasModeFocus(),
) {
    /** The side menu's view, live. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<CanvasPickerState> = combine(focus.current, resolver.supported) { target, supported -> target.takeIf { supported } }
        .flatMapLatest { target ->
            if (target == null) flowOf(CanvasPickerState()) else resolver.state(target).map { CanvasPickerState(target, it.mountedCanvas) }
        }

    /** The conversation a pick would mount on now, or null when a pick opens as before. */
    fun mountTarget(): ConversationCanvasTarget? = focus.current.value.takeIf { resolver.supported.value }

    /** Mounts [canvas] on the conversation in canvas mode, or says to open it as before. */
    suspend fun pick(canvas: CanvasId): CanvasPick {
        val target = mountTarget() ?: return CanvasPick.OpenAsBefore
        return outcome(resolver.mount(target, canvas))
    }

    /** Puts the conversation in canvas mode back on its own board. */
    suspend fun useOwnCanvas(): CanvasPick {
        val target = mountTarget() ?: return CanvasPick.OpenAsBefore
        return outcome(resolver.unmount(target))
    }

    private fun outcome(change: CanvasMountChange): CanvasPick = when (change) {
        is CanvasMountChange.Applied -> CanvasPick.Mounted(change)
        is CanvasMountChange.Failed -> CanvasPick.Failed(change.problem)
    }
}
