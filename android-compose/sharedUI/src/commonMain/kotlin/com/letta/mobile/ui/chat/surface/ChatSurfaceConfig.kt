package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatDisplayMode

/**
 * letta-mobile-bglj6.1: how the shared chat page looks, set by the host from its own
 * preferences (font scale store, haptics setting, debug mode).
 */
@Immutable
data class ChatSurfaceAppearance(
    val displayMode: ChatDisplayMode = ChatDisplayMode.Interactive,
    /** Timeline text scale; pinch-to-zoom reports changes through ChatActions.setFontScale. */
    val fontScale: Float = 1f,
    /**
     * True when the host already scales the page's text by [fontScale] (desktop does it
     * through the window density). The page then keeps [fontScale] only as the baseline
     * for pinch-to-zoom and must not scale text again.
     */
    val fontScaleAppliedByHost: Boolean = false,
    val hapticsEnabled: Boolean = true,
    /** The host's font-scale range: pinch-to-zoom clamps to it (Android 0.7–1.6, desktop 0.8–2.0). */
    val fontScaleRange: ClosedFloatingPointRange<Float> = DefaultFontScaleRange,
    /** Where a run's tool calls open from their "Ran 2 commands" line (Android: a sheet). */
    val toolDetails: ChatToolDetails = ChatToolDetails.Inline,
    /**
     * The host's input idiom (letta-mobile-bglj6.1.9). Touch draws the phone's chat: the flush
     * full-width composer bar, the "+" action sheet, the canvas's bottom bar with a floating chat
     * head. Pointer keeps the desktop card, the floating panel and the minimised mascot.
     */
    val platformStyle: ChatPlatformStyle = ChatPlatformStyle.Pointer,
)

/**
 * letta-mobile-bglj6.1.9: which idiom the page is drawn in. Keyed off the platform, not the
 * window width: a narrow desktop window is still driven by a pointer, a tablet still by touch.
 */
enum class ChatPlatformStyle {
    /** Phones and tablets: the legacy Android composer bar, sheets, chat head over the canvas. */
    Touch,

    /** Desktop: the composer card, popup menus, the movable docked panel. */
    Pointer,
}

/** The page's [ChatPlatformStyle], for parts that sit deep under the page (the composer, rows). */
internal val LocalChatPlatformStyle = androidx.compose.runtime.staticCompositionLocalOf { ChatPlatformStyle.Pointer }

/** True when the page is drawn in the [ChatPlatformStyle.Touch] idiom. */
@Composable
internal fun touchStyle(): Boolean = LocalChatPlatformStyle.current == ChatPlatformStyle.Touch

/**
 * letta-mobile-bglj6.1: how a tool summary line ("Ran 2 commands") shows its calls.
 * A bottom sheet is a touch idiom; a pointer host expands them in place.
 */
enum class ChatToolDetails {
    /** The line is a disclosure: the tool cards expand under it (desktop). */
    Inline,

    /** The line opens the tool cards in a modal bottom sheet (Android). */
    Sheet,
}

/** Android's settings range, the default for hosts that do not set their own. */
val DefaultFontScaleRange: ClosedFloatingPointRange<Float> = 0.7f..1.6f

/**
 * What only a platform can provide, injected as slots so the page itself stays in commonMain.
 *
 * Every member is optional: a null slot hides the affordance.
 */
@Immutable
data class ChatSurfacePlatform(
    /**
     * A microphone button for dictation (Android's speech recognizer). It calls `onDictated`
     * with recognised text, which the composer appends to the draft.
     */
    val voiceInput: (@Composable (onDictated: (String) -> Unit) -> Unit)? = null,
    /**
     * Draws the full-screen page's background (the hosts' ambient agent glow) around its
     * content. Null uses the theme background. It is the page's own layer because, over a
     * canvas, the page must be opaque.
     */
    val pageBackground: (@Composable (content: @Composable () -> Unit) -> Unit)? = null,
    /**
     * Whether the composer shows its keyboard-shortcut strip ("Enter or Ctrl+Enter to send").
     * True where a hardware keyboard is the norm (desktop); false on touch-first hosts.
     */
    val showKeyboardHints: Boolean = true,
    /**
     * Drawn over the full-screen timeline (a top-aligned box over the list): host chrome
     * the shared page does not own, such as Android's active-subagent rings.
     */
    val timelineOverlay: (@Composable () -> Unit)? = null,
    /**
     * Drawn over the Touch canvas while it is the view (a box over the board, below
     * [topChromeInset] and above the chat bar): host chrome the canvas mode must keep in sight,
     * such as Android's active-subagent rings (letta-mobile-bglj6.1.22). Not drawn on the
     * full-screen page, which has [timelineOverlay].
     */
    val canvasOverlay: (@Composable () -> Unit)? = null,
    /**
     * Told the full-screen composer's measured height whenever it changes, so a [pageBackground]
     * that keeps clear of the composer (Android's ambient glow) can follow it.
     */
    val onComposerHeightChange: ((Dp) -> Unit)? = null,
    /**
     * Host chrome floating over the top of the page, measured from its top edge (Android: the
     * status bar and the chat screen's header pills). The page still draws edge to edge under it:
     * the full-screen timeline scrolls behind it with its rows resting below it, and the Touch chat
     * head keeps below it. Zero where nothing floats over the page (desktop).
     */
    val topChromeInset: Dp = 0.dp,
) {
    companion object {
        val Default = ChatSurfacePlatform()
    }
}
