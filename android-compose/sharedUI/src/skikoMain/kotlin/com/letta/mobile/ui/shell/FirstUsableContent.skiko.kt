package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable

/** The desktop and the browser have no launch-time "fully drawn" signal to feed. */
@Composable
internal actual fun ReportHostDrawnWhen(drawn: Boolean) = Unit
