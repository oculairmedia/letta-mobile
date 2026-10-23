package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

/** A desktop window is driven by a mouse or trackpad; a narrow window still draws Touch through [lettaIdiom]. */
@Composable
actual fun rememberPrimaryPointer(): LettaInput = LettaInput.Pointer
