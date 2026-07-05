package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-h30cy diagnostic infrastructure for the dropped-character bug.
 *
 * The symptom: the streamed assistant row's reducer-INPUT text is missing
 * characters (leading fragment fixed via replay=1; a residual mid-stream
 * single-char / punctuation drop remains). The wire is complete (proven via
 * app-server-iroh-probe --dump-frames) and the reducer is clean (proven via
 * IrohRealFrameReplayTest), so a fragment is lost ON DEVICE somewhere in the
 * events-flow between transport emit and reducer ingest.
 *
 * This records, per assistant otid/run, the CONTENT LENGTH observed at each named
 * gate in the flow. Comparing the max length at gate1 (emit) vs gate4 (ingest) —
 * and any intermediate gate — pinpoints the exact hop where characters vanish.
 * Flag-gated (LettaFrameFlowDiag at VERBOSE) so it is zero-cost when off.
 *
 * Usage: call [record] at each instrumented hop with the frame's otid (or msg id
 * when otid is absent), the message type, and the CURRENT content string. The
 * diagnostic logs the per-gate length and whether it REGRESSED vs the last length
 * seen at the previous gate for the same key (a regression at a gate boundary =
 * the drop point).
 */
object IrohFrameFlowDiagnostics {

    private const val TAG = "FrameFlowDiag"

    fun enabled(): Boolean = Telemetry.isFrameFlowDiagEnabled()

    /**
     * Record the content length observed for [key] (otid/msgId) at [gate].
     * Logs the length and, if this gate's length is SHORTER than the longest
     * length previously recorded for this key, flags a `contentRegressed` at the
     * gate — that is the hop where characters were dropped.
     */
    fun record(gate: String, key: String, messageType: String, content: String) {
        if (!enabled()) return
        val len = content.length
        Telemetry.event(
            TAG, "gate",
            "gate" to gate,
            "key" to key,
            "type" to messageType,
            "len" to len,
            // tail so we can see WHICH characters are present at this gate
            "tail" to content.takeLast(24).replace("\n", "\\n"),
        )
    }
}
