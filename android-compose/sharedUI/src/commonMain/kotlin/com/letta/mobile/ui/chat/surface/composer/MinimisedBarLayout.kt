package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth

/**
 * The minimised dock's own say over its prompt bar (letta-mobile-zxk2n.29), where the host keeps
 * layout prefs: the bar runs as wide as the person dragged it (not capped at the chat column), its
 * field as tall as they dragged its top edge, and [handles] draws the drag strips over the bar's
 * own edges. Null everywhere else: the open panel's bar, the full page and the phone.
 */
@Immutable
internal class MinimisedBarLayout(
    /** The prompt field's height; null keeps the one-line bar. */
    val fieldHeight: Dp?,
    /** The bar's resize strips, laid over the bar itself; null while the dock folds. */
    val handles: (@Composable BoxScope.() -> Unit)?,
)

internal val LocalMinimisedBarLayout = compositionLocalOf<MinimisedBarLayout?> { null }

/** How wide the bar (and its companion row) may run: the person's width when minimised, else the chat column. */
@Composable
internal fun composerBarMaxWidth(): Dp = if (LocalMinimisedBarLayout.current != null) Dp.Infinity else ChatColumnMaxWidth

/** The docked bar's field: one line, or a box of [fieldHeight] the person dragged open. */
internal fun dockedFieldStyle(base: ComposerFieldStyle, fieldHeight: Dp?): ComposerFieldStyle {
    if (fieldHeight == null) return base
    return base.copy(
        minHeight = fieldHeight,
        maxHeight = fieldHeight,
        singleLine = false,
        maxLines = Int.MAX_VALUE,
        textAlignment = Alignment.TopStart,
    )
}
