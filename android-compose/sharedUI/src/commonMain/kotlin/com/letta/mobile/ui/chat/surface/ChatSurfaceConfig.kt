package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
)

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
) {
    companion object {
        val Default = ChatSurfacePlatform()
    }
}
