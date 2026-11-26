package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue

/**
 * Which telemetry events the flight record keeps and what of them: connection, turn and feed state, never content. An event
 * is kept when its tag/name is listed in [RECORDED] or it is a warning or an error; debug events never are. Of a kept event
 * only ids, seqs, states, counts and timings survive: an attribute whose name says it carries text, a payload, a credential,
 * an address or a free-form error message is dropped ([isContentKey]), text values are cut to [MAX_TEXT_CHARS] on one
 * line, and a throwable keeps only its class (its message stays out).
 */
internal object FlightRecordPolicy {
    /** Whether the record keeps [event] at all (before rate limiting). */
    fun admits(event: Telemetry.Event): Boolean {
        if (event.level == Telemetry.Level.DEBUG) return false
        if (event.level == Telemetry.Level.WARN || event.level == Telemetry.Level.ERROR) return true
        if (event.tag !in RECORDED) return false
        val names = RECORDED.getValue(event.tag)
        return names == null || event.name in names
    }

    /** [event] as one record line, its attributes cut down to what the record may keep; [suppressed] repeats are named on it. */
    fun lineFor(event: Telemetry.Event, suppressed: Int): String {
        val kept = event.attrs.entries
            .filterNot { (key, _) -> isContentKey(key) }
            .associate { (key, value) -> key to sanitized(value) }
        val classOnly = event.throwable?.let { mapOf(ERROR_CLASS to TelemetryValue.Text(it::class.simpleName ?: "Throwable")) }
        val counted = if (suppressed > 0) mapOf(SUPPRESSED to TelemetryValue.Numeric(suppressed)) else emptyMap()
        return event.copy(attrs = kept + classOnly.orEmpty() + counted, throwable = null).toLine()
    }

    /**
     * Whether an attribute called [key] may carry content. A name ending in an id, seq, count or timing suffix is a number or
     * an id and is kept; any other name containing a [CONTENT_FRAGMENTS] word is dropped.
     */
    fun isContentKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        if (SAFE_SUFFIXES.any(normalized::endsWith)) return false
        return CONTENT_FRAGMENTS.any(normalized::contains)
    }

    private fun sanitized(value: TelemetryValue): TelemetryValue = when (value) {
        is TelemetryValue.Text -> TelemetryValue.Text(value.value.take(MAX_TEXT_CHARS).replace(LINE_BREAK, " "))
        is TelemetryValue.NumericList -> TelemetryValue.NumericList(value.value.take(MAX_LIST_ITEMS))
        is TelemetryValue.Numeric, is TelemetryValue.Flag, TelemetryValue.Null -> value
    }

    /** Tag to the event names kept under it; null keeps every name. */
    private val RECORDED: Map<String, Set<String>?> = mapOf(
        FlightRecording.TAG to null,
        "IrohSupervisor" to null,
        "IrohTurn" to null,
        "IrohObserver" to null,
        "FeedTimeline" to null,
        "SubagentRepo" to null,
        "CanonicalTimeline" to null,
        "IrohTransport" to setOf(
            "connect.ok", "connect.failed", "connection.closed", "control.opened", "stream.opened", "frame.reader_error",
            "frame.protocol_error", "keepalive.stopped", "crash.caught", "paths.changed", "turn.cutoff_by_session_loss",
            "turn.cancelled", "turn.run_id_promoted", "cancel.begin", "cancel.queued_turn",
        ),
        "IrohTrace" to setOf(
            "transport.connect.begin", "transport.connect.done", "transport.send.called",
            "coordinator.ensureConnected.begin", "coordinator.ensureConnected.done",
        ),
        "TimelineSync" to setOf(
            "streamSubscriber.activeChanged", "streamSubscriber.opened", "streamSubscriber.closed",
            "streamSubscriber.silenceTimeout", "streamSubscriber.watchdogReconnect", "streamSubscriber.idle404",
            "terminal.settlement.fence",
        ),
    )

    private val SAFE_SUFFIXES = listOf("id", "ids", "seq", "count", "ms", "bytes", "size", "length", "epoch", "index", "class")

    private val CONTENT_FRAGMENTS = listOf(
        "text", "content", "message", "body", "prompt", "argument", "args", "result", "output", "input", "payload", "delta",
        "token", "ticket", "secret", "password", "passphrase", "auth", "cookie", "credential", "key", "url", "uri", "header",
        "query", "error",
    )

    private val LINE_BREAK = Regex("[\\r\\n]+")
    private const val MAX_TEXT_CHARS = 120
    private const val MAX_LIST_ITEMS = 16
    private const val ERROR_CLASS = "errorClass"
    private const val SUPPRESSED = "suppressed"
}

/**
 * At most [maxPerWindow] lines per tag/name in each [windowMs] of event time; past that a key is silent until its next window,
 * whose first line says how many were left out. Event time, not wall time, so a test replays it exactly.
 */
internal class FlightRateLimiter(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
) {
    private class Window(var startMs: Long, var written: Int = 0, var suppressed: Int = 0)

    private val windows = HashMap<String, Window>()

    /** The number of repeats suppressed before [event] when it may be written, else null. The caller serializes calls. */
    fun admit(event: Telemetry.Event): Int? {
        val key = "${event.tag}/${event.name}"
        val window = windows.getOrPut(key) { Window(event.timestampMs) }
        if (event.timestampMs - window.startMs >= windowMs) {
            val carried = window.suppressed
            window.startMs = event.timestampMs
            window.written = 1
            window.suppressed = 0
            return carried
        }
        if (window.written >= maxPerWindow) {
            window.suppressed += 1
            return null
        }
        window.written += 1
        return 0
    }

    private companion object {
        const val MAX_PER_WINDOW = 10
        const val WINDOW_MS = 10_000L
    }
}
