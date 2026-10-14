package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.controller.RuntimeSyncScheduler
import com.letta.mobile.data.controller.SyncBudgetPolicy
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Clock

/**
 * How a client puts a [StreamIntegrityMonitor] on its [AppServerRuntimeEventRouter]: recovery and
 * cadence syncs go out through [client], cadence syncs only as far as [budget] allows.
 */
class StreamIntegrityBinding(
    private val client: AppServerClient,
    private val requestIdFactory: () -> String,
    private val budget: SyncBudgetPolicy = SyncBudgetPolicy.Unlimited,
) {
    /** The monitor for [router]'s watched runtimes; not yet bound or started. */
    fun monitorFor(router: AppServerRuntimeEventRouter, detectGaps: Boolean): StreamIntegrityMonitor {
        val scheduler = RuntimeSyncScheduler(
            runtimes = router::watchedRuntimes,
            sync = { runtime, reason ->
                client.sync(
                    AppServerCommand.Sync(
                        runtime = runtime,
                        requestId = requestIdFactory(),
                        recoverApprovals = reason.recoverApprovals,
                        forceDeviceStatus = reason.recoverApprovals,
                    ),
                )
            },
            clock = { Clock.System.now().toEpochMilliseconds() },
            budget = budget,
        )
        return StreamIntegrityMonitor(scheduler, detectGaps, router.connectionGenerationProvider)
    }

    /** Binds a monitor to [router] and runs its sync loop in [scope]; cancel the job to stop it. */
    fun bindAndStart(router: AppServerRuntimeEventRouter, detectGaps: Boolean, scope: CoroutineScope): Job {
        val monitor = monitorFor(router, detectGaps)
        router.bindStreamIntegrity(monitor)
        return monitor.start(scope)
    }

    /**
     * letta-mobile-by4vm.50: an Iroh host relays frames per viewer and stamps each connection's
     * `event_seq` itself. Only a host that advertises
     * [StreamIntegrityMonitor.EVENT_SEQ_ORDERED_CAPABILITY] draws that seq in wire order, so only
     * then is a jump a lost frame. Any other host keeps today's behaviour: no monitor at all
     * (null), so no gap detection and no cadence syncs either.
     */
    fun bindIfHostOrdersEventSeq(
        router: AppServerRuntimeEventRouter,
        hostCapabilities: Set<String>?,
        scope: CoroutineScope,
    ): Job? {
        if (StreamIntegrityMonitor.EVENT_SEQ_ORDERED_CAPABILITY !in hostCapabilities.orEmpty()) return null
        return bindAndStart(router, detectGaps = true, scope = scope)
    }
}
