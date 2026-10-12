package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.ui.components.LettaNoticeAction
import com.letta.mobile.ui.components.LettaNoticeBanner

/**
 * Container (letta-mobile-ckmub.3.12): binds the bundled runtime's health to the shared notice
 * banner over the chat pane: "restarting" during backoff, Restart once automatic restarts gave up,
 * Start when the user stopped it (F03). Shows nothing while the runtime is healthy.
 */
@Composable
internal fun DesktopLocalRuntimeBanner(modifier: Modifier = Modifier) {
    val health by DesktopLocalRuntimeHost.health.collectAsState()
    if (!health.needsChatBanner) return
    val action = when {
        health.stoppedByUser -> LettaNoticeAction("Start", DesktopLocalRuntimeHost::startByUser)
        health.needsForceRestart -> LettaNoticeAction("Restart", DesktopLocalRuntimeHost::forceRestart)
        else -> null
    }
    LettaNoticeBanner(text = "Local runtime: ${health.describe()}", modifier = modifier, action = action)
}
