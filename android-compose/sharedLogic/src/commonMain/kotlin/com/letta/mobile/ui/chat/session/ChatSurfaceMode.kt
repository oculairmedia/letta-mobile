package com.letta.mobile.ui.chat.session

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bglj6.1: where the shared chat page is drawn.
 *
 * - [Docked]: a bar at the bottom of the canvas. The canvas is the default view.
 * - [FullScreen]: the traditional chat page; the canvas is hidden behind it.
 * - [Floating]: an in-app floating panel over the canvas. Reserved: it opens only on an
 *   explicit action, and only where [ChatSurfacePresentation.floatingEnabled] is true.
 *   An external (system bubble / separate window) mode is a separate epic.
 */
@Serializable
enum class ChatSurfaceMode {
    @SerialName("docked")
    Docked,

    @SerialName("full_screen")
    FullScreen,

    @SerialName("floating")
    Floating,
}

/** Why the presentation changes. Sending a message is deliberately NOT one of these. */
@Serializable
sealed interface ChatSurfaceIntent {
    /** Tap or drag the docked bar open, or the "expand" control on the floating panel. */
    @Serializable
    @SerialName("expand")
    data object Expand : ChatSurfaceIntent

    /**
     * Swipe up on the full-screen prompt card, or its "Open canvas" item. Keeps the shipped
     * #1614 direction: full-screen chat -> canvas.
     */
    @Serializable
    @SerialName("open_canvas")
    data object OpenCanvas : ChatSurfaceIntent

    /** The explicit return control on the full-screen page (back, or "collapse"). */
    @Serializable
    @SerialName("collapse")
    data object Collapse : ChatSurfaceIntent

    /** Explicitly float the conversation over the canvas. Never raised by a send. */
    @Serializable
    @SerialName("float")
    data object OpenFloating : ChatSurfaceIntent

    /** Dismiss the floating panel. Collapses to the dock; the conversation lives on. */
    @Serializable
    @SerialName("dismiss_floating")
    data object DismissFloating : ChatSurfaceIntent
}

/**
 * The presentation state machine. It owns only where the page is drawn: the draft, run and
 * queue live in [ChatSessionPort] and so survive every transition unchanged.
 */
@Immutable
@Serializable
data class ChatSurfacePresentation(
    val mode: ChatSurfaceMode = ChatSurfaceMode.Docked,
    /** Whether [ChatSurfaceMode.Floating] may be entered. Off until in-app floating ships. */
    val floatingEnabled: Boolean = false,
) {
    /** The canvas is on screen (behind the dock or the floating panel). */
    val canvasVisible: Boolean get() = mode != ChatSurfaceMode.FullScreen

    companion object {
        /** Canvas-first: the default view is the canvas with the chat docked. */
        val CanvasFirst = ChatSurfacePresentation(mode = ChatSurfaceMode.Docked)

        /** Traditional chat: the optional full-screen page. */
        val ChatFirst = ChatSurfacePresentation(mode = ChatSurfaceMode.FullScreen)

        /**
         * How a conversation first opens: on the canvas when the "Open conversations on the
         * canvas" preference is on and the host has a canvas to dock under, otherwise the
         * traditional full-screen chat. Only the initial value; later intents (and any saved
         * mode) win after that.
         */
        fun initial(openOnCanvas: Boolean, hasCanvas: Boolean): ChatSurfacePresentation =
            if (openOnCanvas && hasCanvas) CanvasFirst else ChatFirst
    }
}

object ChatSurfaceModeReducer {
    /**
     * Pure transition. An intent that does not apply in the current mode returns [state]
     * unchanged (the same instance), so callers can compare by identity to skip work.
     */
    fun reduce(state: ChatSurfacePresentation, intent: ChatSurfaceIntent): ChatSurfacePresentation {
        val next = when (intent) {
            ChatSurfaceIntent.Expand -> when (state.mode) {
                ChatSurfaceMode.Docked, ChatSurfaceMode.Floating -> ChatSurfaceMode.FullScreen
                ChatSurfaceMode.FullScreen -> null
            }
            ChatSurfaceIntent.OpenCanvas, ChatSurfaceIntent.Collapse -> when (state.mode) {
                ChatSurfaceMode.FullScreen -> ChatSurfaceMode.Docked
                ChatSurfaceMode.Docked, ChatSurfaceMode.Floating -> null
            }
            ChatSurfaceIntent.OpenFloating -> when (state.mode) {
                ChatSurfaceMode.Docked -> ChatSurfaceMode.Floating.takeIf { state.floatingEnabled }
                ChatSurfaceMode.FullScreen, ChatSurfaceMode.Floating -> null
            }
            ChatSurfaceIntent.DismissFloating -> when (state.mode) {
                ChatSurfaceMode.Floating -> ChatSurfaceMode.Docked
                ChatSurfaceMode.Docked, ChatSurfaceMode.FullScreen -> null
            }
        }
        return if (next == null || next == state.mode) state else state.copy(mode = next)
    }
}
