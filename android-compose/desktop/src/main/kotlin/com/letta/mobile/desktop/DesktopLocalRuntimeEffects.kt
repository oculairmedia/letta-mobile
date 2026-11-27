package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.controller.reconnect.SuspendEvent
import com.letta.mobile.data.controller.reconnect.SystemSuspendSignal
import com.letta.mobile.data.controller.reconnect.WallClockJumpSuspendSignal
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.runtime.DesktopBundledRuntimeNode
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopRuntimeRestartResponder
import kotlinx.coroutines.launch

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
 * - When the supervisor restarts a crashed child (F03), the desktop's node is restarted over its new
 *   URL through a bridge lease, so the fresh child is not stopped and spawned again by the handoff;
 *   the chat's node session redials the new node by itself. This happens on every backend, not only
 *   while the window shows LOCAL ([DesktopRuntimeRestartResponder]).
 * - On wake (F04), crash counters reset, a dead child restarts, and a dropped chat connection
 *   retries at once.
 */
@Composable
internal fun DesktopLocalRuntimeLifecycleEffect(
    chatController: DesktopChatController,
    suspendSignal: SystemSuspendSignal = remember { WallClockJumpSuspendSignal() },
) {
    LaunchedEffect(chatController) {
        val effectScope = this
        val responder = DesktopRuntimeRestartResponder(
            nodeRunning = { DesktopBundledRuntimeNode.host.running.value },
            handOff = DesktopLocalRuntimeHost::handOffAfterRestart,
            restartNode = { effectScope.launch { DesktopBundledRuntimeNode.restart() } },
        )
        DesktopLocalRuntimeHost.appServer.restarted.collect { responder.onRuntimeRestarted() }
    }
    LaunchedEffect(chatController, suspendSignal) {
        val responder = DesktopSuspendResponder(
            onRuntimeSuspended = DesktopLocalRuntimeHost.appServer::onSuspended,
            onRuntimeResumed = DesktopLocalRuntimeHost.appServer::onResumed,
            onChatResumed = chatController::onSystemResumed,
        )
        suspendSignal.events().collect(responder::handle)
    }
}
