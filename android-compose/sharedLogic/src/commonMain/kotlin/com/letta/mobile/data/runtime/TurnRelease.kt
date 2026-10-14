package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-by4vm.7: why a turn lease was released. Every exit of `AppServerTurnEngine.runTurn`
 * names one of these explicitly; there is no default that a failure can fall through to.
 *
 * The orphan-abort and queued-input-removal decisions ([OrphanedTurnAborter], [QueuedInputRemoval])
 * read these strings, and they are the `releaseReason` telemetry attribute, so the values are wire
 * for diagnostics and must not be renamed.
 */
internal object TurnReleaseReason {
    /** The turn reached a terminal (server, settle window, session cut-off or an abrupt local ending). */
    const val NORMAL_COMPLETION = "normal_completion"

    /** The idle watchdog fired: no frame for the idle window. */
    const val WATCHDOG_TIMEOUT = "watchdog_timeout"

    /** The turn's coroutine was cancelled (user cancel, handover, host scope closing). */
    const val CANCELLATION = "cancellation"

    /** The frame collector failed. */
    const val STREAM_ERROR = "stream_error"

    /** The App Server refused the input, or the connection dropped before its ack. */
    const val INPUT_REJECTED = "input_rejected"

    /** Sending the input threw before any acknowledgement could be read. */
    const val INPUT_SEND_FAILED = "input_send_failed"

    /** Preflight (permission mode) or `runtime_start` failed: no input was ever sent. */
    const val START_FAILED = "start_failed"

    /** The inbound stream finished without a terminal and without an error. */
    const val STREAM_ENDED = "stream_ended"
}

/**
 * letta-mobile-by4vm.7: the release reason and cause of ONE turn lease. The first reason recorded
 * wins: the collector and the input path can both observe the same ending (a stream error cancels
 * the input wait; an input failure cancels the collector), and the one that saw it first knows why.
 */
internal class TurnReleaseRecord {
    var reason: String? = null
        private set
    var cause: Throwable? = null
        private set

    fun record(reason: String, cause: Throwable? = null) {
        if (this.reason != null) return
        this.reason = reason
        this.cause = cause
    }

    /**
     * The recorded reason. A lease released with none recorded is a missed exit path, so it is
     * reported (`activeTurn.releaseReasonMissing`) instead of passing for a normal completion.
     */
    fun reasonForRelease(key: TurnRuntimeKey): String = reason ?: run {
        Telemetry.event(
            "AppServerTurnEngine", "activeTurn.releaseReasonMissing",
            "key" to key.toString(),
            level = Telemetry.Level.ERROR,
        )
        UNRECORDED
    }

    private companion object {
        const val UNRECORDED = "unrecorded"
    }
}

/** The Failed terminal a caller sees when [error] stopped the turn before its input was sent. */
internal fun TurnCommand.startFailedDraft(error: Throwable): RuntimeEventDraft =
    failedDraft("App Server turn could not start: ${error.message ?: error::class.simpleName ?: "unknown error"}")
