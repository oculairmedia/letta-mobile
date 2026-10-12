package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

/** A desktop window keeps its popovers and menus open while it is minimised or unfocused. */
@Composable
internal actual fun DismissWhenOwnerStops(onDismiss: () -> Unit) = Unit
