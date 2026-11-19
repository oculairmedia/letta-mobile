package com.letta.mobile.ui.shell

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** One stable predicate over [drawn]: a new lambda per change would restart the reporter. */
@Composable
internal actual fun ReportHostDrawnWhen(drawn: Boolean) {
    val current: State<Boolean> = rememberUpdatedState(drawn)
    val predicate = remember { { current.value } }
    ReportDrawnWhen(predicate)
}
