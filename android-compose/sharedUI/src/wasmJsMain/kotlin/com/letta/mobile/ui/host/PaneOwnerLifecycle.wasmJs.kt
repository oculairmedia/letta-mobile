package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

/** A browser tab keeps its popovers and menus open while it is hidden, as on desktop. */
@Composable
internal actual fun DismissWhenOwnerStops(onDismiss: () -> Unit) = Unit
