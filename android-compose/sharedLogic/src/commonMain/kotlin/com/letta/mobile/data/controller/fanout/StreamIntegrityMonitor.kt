package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.controller.RuntimeSyncScheduler
import com.letta.mobile.data.controller.RuntimeSyncScheduler.SyncReason
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Clock

/**
 * letta-mobile-bzvro.6 (F06): keeps a client's view of its runtimes from going stale.
 *
 * Fed every inbound frame by [AppServerRuntimeEventRouter] before fan-out. A lost frame (an
 * `event_seq` gap, see [EventSeqGapDetector]) asks the [RuntimeSyncScheduler] for a recovery
 * `sync`; loop status frames set each runtime's cadence (busy vs idle). [requestResync] is the
 * hook for "the app came back to the foreground / the window regained focus".
 */
class StreamIntegrityMonitor(
    private val scheduler: RuntimeSyncScheduler,
    /** Only on transports that deliver every frame of the connection; see [EventSeqGapDetector]. */
    private val detectGaps: Boolean,
    private val connectionGeneration: () -> Long = { 0L },
) {
    private val detector = EventSeqGapDetector()

    fun observe(received: AppServerReceivedFrame) {
        val frame = received.frame
        if (frame is AppServerInboundFrame.UpdateLoopStatus) {
            scheduler.noteLoopStatus(frame.runtime, frame.loopStatus.status)
        }
        if (!detectGaps) return
        val seq = frame.eventSeqOrNull ?: return
        val observation = detector.observe(received.connectionGeneration ?: connectionGeneration(), seq)
        if (observation is EventSeqGapDetector.Observation.Gap) {
            Telemetry.event(
                "StreamIntegrityMonitor", "eventSeq.gap",
                "expected" to observation.expected,
                "received" to observation.received,
                "missing" to observation.missing,
                "frameType" to (frame.type ?: ""),
                level = Telemetry.Level.WARN,
            )
            scheduler.request(SyncReason.Gap)
        }
    }

    /** The person is looking again (app foregrounded, window focused): resync what is on screen. */
    fun requestResync() = scheduler.request(SyncReason.Foreground)

    fun start(scope: CoroutineScope): Job = scheduler.start(scope)

    companion object {
        /**
         * The monitor for [router]'s watched runtimes, syncing through [client]. The caller binds it
         * with [AppServerRuntimeEventRouter.bindStreamIntegrity] and runs it with [start].
         */
        fun forRouter(
            router: AppServerRuntimeEventRouter,
            client: AppServerClient,
            detectGaps: Boolean,
            requestIdFactory: () -> String,
        ): StreamIntegrityMonitor {
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
            )
            return StreamIntegrityMonitor(scheduler, detectGaps, router.connectionGenerationProvider)
        }
    }
}
