package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
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
)

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
     * Told the full-screen composer's measured height whenever it changes, so a [pageBackground]
     * that keeps clear of the composer (Android's ambient glow) can follow it.
     */
    val onComposerHeightChange: ((Dp) -> Unit)? = null,
) {
    companion object {
        val Default = ChatSurfacePlatform()
    }
}
