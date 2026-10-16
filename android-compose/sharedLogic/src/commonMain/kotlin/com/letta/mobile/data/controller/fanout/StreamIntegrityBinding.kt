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
}
