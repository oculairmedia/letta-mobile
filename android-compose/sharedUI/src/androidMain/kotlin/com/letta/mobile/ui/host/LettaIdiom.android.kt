package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

/** A phone or tablet is driven by touch. */
@Composable
actual fun rememberPrimaryPointer(): LettaInput = LettaInput.Touch
