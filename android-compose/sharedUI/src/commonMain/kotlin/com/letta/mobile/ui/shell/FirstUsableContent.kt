package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.shell.ShellDestination
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the shell waits for its first usable content before it tells the host the first frame is
 * as drawn as it will get, so an offline backend that never fills the screen still reports.
 */
val FIRST_USABLE_TIMEOUT: Duration = 10.seconds

/** Why the host was told the first usable content is drawn. */
enum class FirstUsableReason { Content, Timeout }

/**
 * Whether the page the shell draws for [destination] is something to use rather than something to wait
 * behind: Settings (also while the backend is unconfigured) from the first frame, any other page once
 * its source is bound and the backend is there. A connecting or failed backend leaves the connection
 * hero or the waiting page up, which the timeout ([FIRST_USABLE_TIMEOUT]) then covers.
 */
fun shellFirstScreenUsable(
    destination: ShellDestination,
    connection: BackendConnectionStatus,
    sourceReady: Boolean,
): Boolean = when {
    destination is ShellDestination.Settings -> true
    !sourceReady -> false
    else -> when (connection) {
        BackendConnectionStatus.Unconfigured, is BackendConnectionStatus.Connected -> true
        BackendConnectionStatus.Connecting, is BackendConnectionStatus.Failed -> false
    }
}

/**
 * Tells the host the app is fully drawn once [usable], or after [timeout] when it never becomes so, and
 * calls [onReported] once with the reason. It
 * counts as drawn when this leaves the composition too (Android's ReportDrawn contract), so it belongs
 * at one stable place in the shell, never inside a branch that swaps.
 */
@Composable
fun ReportFirstUsableContent(
    usable: Boolean,
    timeout: Duration = FIRST_USABLE_TIMEOUT,
    onReported: (FirstUsableReason) -> Unit = {},
) {
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(timeout)
        timedOut = true
    }
    val reason = when {
        usable -> FirstUsableReason.Content
        timedOut -> FirstUsableReason.Timeout
        else -> null
    }
    // The host records why (content vs timeout) through [onReported]: ui.shell must not import util
    // (import cycle), so the telemetry event is emitted by the caller.
    val report by rememberUpdatedState(onReported)
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(reason) {
        if (reason == null || reported) return@LaunchedEffect
        reported = true
        report(reason)
    }
    ReportHostDrawnWhen(reason != null)
}

/** The host's "fully drawn" signal (Android's FullyDrawnReporter); the desktop and the web have none. */
@Composable
internal expect fun ReportHostDrawnWhen(drawn: Boolean)

/**
 * The shell's first-usable report: the page at [destination] with the backend [connection], once its
 * source is [sourceReady]. Place it at one stable spot ahead of any early return; [onReported]
 * receives the reason once.
 */
@Composable
fun ReportShellFirstUsable(
    destination: ShellDestination,
    connection: BackendConnectionStatus,
    sourceReady: Boolean,
    onReported: (FirstUsableReason) -> Unit = {},
) {
    ReportFirstUsableContent(shellFirstScreenUsable(destination, connection, sourceReady), onReported = onReported)
}
