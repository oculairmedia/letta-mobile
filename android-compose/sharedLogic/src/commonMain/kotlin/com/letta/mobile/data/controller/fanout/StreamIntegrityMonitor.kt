package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.controller.RuntimeSyncScheduler
import com.letta.mobile.data.controller.RuntimeSyncScheduler.SyncReason
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * letta-mobile-bzvro.6 (F06): keeps a client's view of its runtimes from going stale.
 *
 * Fed every inbound frame by [AppServerRuntimeEventRouter] before fan-out. A lost frame (an
 * `event_seq` gap, see [EventSeqGapDetector]) asks the [RuntimeSyncScheduler] for a recovery
 * `sync`; loop status frames set each runtime's cadence (busy vs idle). [requestResync] is the
 * hook for "the app came back to the foreground / the window regained focus".
 *
 * Gap detection needs frames in socket order. The router reads `AppServerClient.events`, which on a
 * direct socket is `AppServerTransport.inboundFrames` in arrival order (letta-mobile-by4vm.5); fed
 * from the old two-lane merge, a `turn_finished` overtaking a delta read as a gap.
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
         * letta-mobile-by4vm.50: advertised in an Iroh host's `auth_response` when it draws every
         * frame's `event_seq` inside its stream write lock, so the per-connection sequence a client
         * sees is contiguous and a jump is a lost frame. Clients enable gap detection on Iroh only
         * when the host says so (see [StreamIntegrityBinding.bindIfHostOrdersEventSeq]).
         */
        const val EVENT_SEQ_ORDERED_CAPABILITY: String = "event_seq_ordered_v1"
    }
}
