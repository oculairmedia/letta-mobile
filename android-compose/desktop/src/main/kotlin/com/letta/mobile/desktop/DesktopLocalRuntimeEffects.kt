package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.controller.reconnect.SuspendEvent
import com.letta.mobile.data.controller.reconnect.SystemSuspendSignal
import com.letta.mobile.data.controller.reconnect.WallClockJumpSuspendSignal
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost

/** Who reacts to sleep and wake on desktop (letta-mobile-bzvro.4, F04). */
internal class DesktopSuspendResponder(
    private val onRuntimeSuspended: () -> Unit,
    private val onRuntimeResumed: () -> Unit,
    private val onChatResumed: () -> Unit,
) {
    fun handle(event: SuspendEvent) {
        when (event) {
            SuspendEvent.Suspended -> onRuntimeSuspended()
            is SuspendEvent.Resumed -> {
                // Runtime first: a child that died in the sleep restarts before the chat redials.
                onRuntimeResumed()
                onChatResumed()
            }
        }
    }
}

/**
 * Binds the bundled runtime's supervisor and the system's sleep/wake to the chat:
 *
 * - When the supervisor restarts a crashed child (F03), the chat reconnects to its new URL through
 *   a bridge lease, so the fresh child is not stopped and spawned again by the handoff.
 * - On wake (F04), crash counters reset, a dead child restarts, and a dropped chat connection
 *   retries at once.
 */
@Composable
internal fun DesktopLocalRuntimeLifecycleEffect(
    chatController: DesktopChatController,
    isLocalMode: Boolean,
    suspendSignal: SystemSuspendSignal = remember { WallClockJumpSuspendSignal() },
) {
    val localMode by rememberUpdatedState(isLocalMode)
    LaunchedEffect(chatController) {
        DesktopLocalRuntimeHost.restarted.collect {
            if (localMode) DesktopLocalRuntimeHost.handOffAfterRestart(chatController::retryConnection)
        }
    }
    LaunchedEffect(chatController, suspendSignal) {
        val responder = DesktopSuspendResponder(
            onRuntimeSuspended = DesktopLocalRuntimeHost::onSystemSuspended,
            onRuntimeResumed = DesktopLocalRuntimeHost::onSystemResumed,
            onChatResumed = chatController::onSystemResumed,
        )
        suspendSignal.events().collect(responder::handle)
    }
}
