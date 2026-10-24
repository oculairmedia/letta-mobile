package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost

/**
 * How the shared chat page looks, set by the host from its own
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
    /**
     *  (G91): which rows and readouts the timeline leaves out (reasoning,
     * compaction summaries, token usage). The host reads them from its settings.
     */
    val displayOptions: ChatDisplayOptions = ChatDisplayOptions.Default,
)

/**
 * True when the page is drawn in the Touch idiom: the flush full-width composer bar, the "+"
 * action sheet, the canvas's bottom bar with a floating chat head. Otherwise the pointer idiom: the
 * composer card, the floating panel and the minimised mascot. It is the host's resolved idiom
 * ([com.letta.mobile.ui.host.lettaIdiom]), so the chat and the shell's containers never disagree.
 */
@Composable
internal fun touchStyle(): Boolean = LocalLettaHost.current.input == LettaInput.Touch

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
     * such as Android's active-subagent rings. Not drawn on the
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
    /**
     * Save and share for the full-screen image viewer (Android: MediaStore and the share sheet).
     * Null hides both actions (desktop).
     */
    val imageActions: ChatImageActions? = null,
) {
    companion object {
        val Default = ChatSurfacePlatform()
    }
}

/** What the image viewer can do with the image on screen, beyond looking at it. */
@Immutable
class ChatImageActions(
    val save: (UiImageAttachment) -> Unit,
    val share: (UiImageAttachment) -> Unit,
)

/** The page's [ChatSurfacePlatform.imageActions], for the viewer deep under the timeline. */
internal val LocalChatImageActions = androidx.compose.runtime.staticCompositionLocalOf<ChatImageActions?> { null }
