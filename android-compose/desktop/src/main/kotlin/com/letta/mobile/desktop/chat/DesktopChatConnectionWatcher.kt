package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitors the active [DesktopChatGateway] connection state, tracks outages,
 * triggers auto-recovery when connection returns, and escalates sustained outages.
 */
internal class DesktopChatConnectionWatcher(
    private val scope: CoroutineScope,
    private val onConnected: suspend () -> Unit,
    private val onDisconnected: (transportState: ChannelTransportState.Disconnected) -> Unit,
    private val onEscalateRetryConnection: () -> Unit,
) {
    private var connectionJob: Job? = null

    @Volatile
    private var outageEscalated = false

    fun start(gateway: DesktopChatGateway?) {
        connectionJob?.cancel()
        connectionJob = null
        val statusGateway = gateway as? ConnectionStatusGateway ?: return
        val outage = OutageTracker()
        connectionJob = scope.launch {
            val escalationWatchdog = launch { watchForSustainedOutage(outage) }
            try {
                statusGateway.connectionState.collect { transportState ->
                    when (transportState) {
                        is ChannelTransportState.Connected -> onTransportConnected(outage)
                        is ChannelTransportState.Disconnected -> onTransportDisconnected(transportState, outage)
                        else -> Unit
                    }
                }
            } finally {
                escalationWatchdog.cancel()
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private class OutageTracker {
        val sawDisconnect = AtomicBoolean(false)
        val isDown = AtomicBoolean(false)
        val downTicks = AtomicInteger(0)

        fun markUp() {
            isDown.set(false)
            downTicks.set(0)
        }
    }

    private suspend fun watchForSustainedOutage(outage: OutageTracker) {
        while (true) {
            delay(CONNECTION_ESCALATION_POLL_MS)
            if (!outage.isDown.get()) {
                outage.downTicks.set(0)
                continue
            }
            if (outageEscalated) continue
            val downMs = outage.downTicks.incrementAndGet() * CONNECTION_ESCALATION_POLL_MS
            if (downMs < CONNECTION_ESCALATION_MS) continue
            outageEscalated = true
            onEscalateRetryConnection()
            return
        }
    }

    private suspend fun onTransportConnected(outage: OutageTracker) {
        outage.markUp()
        outageEscalated = false
        if (!outage.sawDisconnect.compareAndSet(true, false)) return
        onConnected()
    }

    private fun onTransportDisconnected(
        transportState: ChannelTransportState.Disconnected,
        outage: OutageTracker,
    ) {
        if (transportState.isAuthFailure) {
            outage.markUp()
        } else {
            outage.sawDisconnect.set(true)
            outage.isDown.set(true)
        }
        onDisconnected(transportState)
    }

    companion object {
        private const val CONNECTION_ESCALATION_MS = 60_000L
        private const val CONNECTION_ESCALATION_POLL_MS = 1_000L
    }
}
