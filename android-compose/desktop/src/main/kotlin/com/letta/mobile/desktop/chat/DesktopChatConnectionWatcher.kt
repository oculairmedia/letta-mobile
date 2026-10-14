package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

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

    @Volatile
    private var currentOutage: OutageTracker? = null

    /** letta-mobile-by4vm.9: the bound gateway redials on its own; only its give-up escalates. */
    @Volatile
    private var reconnectOwner: DesktopReconnectOwningGateway? = null

    fun start(gateway: DesktopChatGateway?) {
        connectionJob?.cancel()
        connectionJob = null
        currentOutage = null
        reconnectOwner = (gateway as? DesktopReconnectOwningGateway)?.takeIf { it.ownsReconnect }
        val statusGateway = gateway as? ConnectionStatusGateway ?: return
        val outage = OutageTracker()
        currentOutage = outage
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
        currentOutage = null
        reconnectOwner = null
    }

    /**
     * The machine woke from sleep (letta-mobile-bzvro.4, F04). A gateway that reconnects itself is
     * told to redial now (letta-mobile-by4vm.9). Otherwise a connection that is down now is almost
     * certainly the sleep's doing, so retry at once instead of waiting out the sustained outage
     * window. Returns true when a retry was triggered.
     */
    fun onSystemResumed(): Boolean {
        if (reconnectOwner?.resumeSession() == true) return true
        val outage = currentOutage ?: return false
        if (!outage.isDown.get()) return false
        outageEscalated = true
        onEscalateRetryConnection()
        return true
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
            delay(CONNECTION_ESCALATION_POLL_MS.milliseconds)
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
        } else if (transportState.willReconnect && reconnectOwner != null) {
            // The gateway's own owner is redialling; rebuilding it would kill the turns it keeps.
            outage.sawDisconnect.set(true)
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
