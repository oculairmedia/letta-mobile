package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable

/**
 * letta-mobile-y5q9z: the system Back while the canvas bubble's card is open folds it back (the
 * recents first, then the card), as Back closes an expanded Android bubble. Android binds the
 * activity's back dispatcher; desktop and web have no system Back.
 */
@Composable
internal expect fun CanvasBubbleBackHandler(enabled: Boolean, onBack: () -> Unit)
