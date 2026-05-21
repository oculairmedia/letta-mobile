package com.letta.mobile.util

import android.util.Log
import androidx.tracing.Trace
import com.letta.mobile.core.BuildConfig
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Context parameter wrapper for [Telemetry]. When declared as a context
 * parameter on a function, callers must provide [TelemetryContext] in scope
 * and can call [event], [error], [measure], and [startTimer] without
 * importing [Telemetry] directly.
 *
 * Usage:
 * ```
 * context(TelemetryContext)
 * fun doWork() {
 *     event("MyTag", "started")
 *     val result = measure("MyTag", "computation") { heavyLifting() }
 * }
 * ```
 *
 * See: letta-mobile-925m.3
 */
class TelemetryContext {
    inline fun event(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        durationMs: Long? = null,
        level: Telemetry.Level = Telemetry.Level.INFO,
    ) = Telemetry.event(tag, name, *attrs, durationMs = durationMs, level = level)

    inline fun error(
        tag: String,
        name: String,
        throwable: Throwable,
        vararg attrs: Pair<String, Any?>,
    ) = Telemetry.error(tag, name, throwable, *attrs)

    inline fun <T> measure(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        block: () -> T,
    ): T = Telemetry.measure(tag, name, *attrs, block = block)

    inline fun startTimer(tag: String, name: String): Telemetry.Timer =
        Telemetry.startTimer(tag, name)
}

/**
 * Telemetry — unified event stream for latency, lifecycle, and errors.
 *
 * Design goals:
 * - **Zero setup for callers.** Call [Telemetry.event] from anywhere, no DI.
 * - **Structured data.** Events carry a tag (subsystem), name (verb),
 *   optional duration, and a bag of key/value attributes.
 * - **Multiple sinks.** Keeps the most recent 1000 events in a ring buffer so
 *   a Dev screen can display them. Logcat mirroring is independently gated and
 *   defaults to debug builds only.
 * - **Cheap.** Producing an event is lock-free (ConcurrentLinkedDeque). A
 *   single AtomicBoolean gates emission entirely for release builds.
 *
 * Usage patterns:
 *
 * ```
 * // Simple point event
 * Telemetry.event("TimelineSync", "localAppended", "otid" to otid)
 *
 * // Measured block
 * val result = Telemetry.measure("TimelineSync", "hydrate") {
 *     messageApi.listConversationMessages(...)
 * }
 *
 * // Manual timer (when the end is in a different coroutine/callback)
 * val timer = Telemetry.startTimer("Send", "roundtrip")
 * // ... later ...
 * timer.stop("otid" to otid, "events" to eventCount)
 * ```
 */
object Telemetry {
    /**
     * Master switch. Flip to `false` in release builds to make every call a
     * no-op (single volatile read, no allocation).
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val enabled = AtomicBoolean(true)

    /**
     * Logcat mirror switch. Metrics collection should not require logging, so
     * release builds keep collecting into the in-memory ring while skipping
     * string formatting and Logcat writes by default.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val logcatEnabled = AtomicBoolean(BuildConfig.DEBUG)

    /**
     * Per-event timeline state dump after every hydrate / reconcile / stream
     * ingest. OFF by default — when on, every dump emits one Telemetry event
     * per timeline entry, which is high-volume. Use only while diagnosing
     * hydration duplication bugs like letta-mobile-1ar3u / 3j6 / 16li.
     *
     * Single volatile read on the hot path when off.
     *
     * Flip from the TelemetryScreen (debug builds), or via adb without a
     * rebuild: `adb shell setprop log.tag.LettaTimelineDump VERBOSE`. The
     * setprop check runs lazily inside [isTimelineDumpEnabled].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val timelineDumpEnabled = AtomicBoolean(false)

    /**
     * Tag used for the adb setprop override.
     * `adb shell setprop log.tag.LettaTimelineDump VERBOSE` flips the dump on
     * without needing the in-app toggle. Read via [android.util.Log.isLoggable]
     * which caches per process, so the cost is a single int compare.
     */
    private const val TIMELINE_DUMP_TAG = "LettaTimelineDump"

    fun isTimelineDumpEnabled(): Boolean =
        timelineDumpEnabled.get() || Log.isLoggable(TIMELINE_DUMP_TAG, Log.VERBOSE)

    private const val TAG_PREFIX = "Telemetry"
    private const val MAX_RING_SIZE = 1000

    private val ring = ConcurrentLinkedDeque<Event>()
    private val ringLock = Any()
    private val _eventsFlow = MutableStateFlow<List<Event>>(emptyList())

    /**
     * Snapshot of the last N events (newest first). Backed by a StateFlow so
     * the dev UI can observe without polling.
     */
    val events: StateFlow<List<Event>> = _eventsFlow.asStateFlow()

    /** Record a single event. Optional [durationMs] for phase measurements. */
    fun event(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        durationMs: Long? = null,
        level: Level = Level.INFO,
    ) {
        if (!enabled.get()) return

        val ev = Event(
            timestampMs = System.currentTimeMillis(),
            tag = tag,
            name = name,
            durationMs = durationMs,
            attrs = attrs.toMap(),
            level = level,
        )
        emit(ev)
    }

    /** Record an error event with throwable detail. */
    fun error(
        tag: String,
        name: String,
        throwable: Throwable,
        vararg attrs: Pair<String, Any?>,
    ) {
        if (!enabled.get()) return

        val allAttrs = attrs.toMutableList()
        allAttrs += ("errorClass" to throwable.javaClass.simpleName)
        allAttrs += ("errorMessage" to (throwable.message ?: "<none>"))
        val ev = Event(
            timestampMs = System.currentTimeMillis(),
            tag = tag,
            name = name,
            durationMs = null,
            attrs = allAttrs.toMap(),
            level = Level.ERROR,
            throwable = throwable,
        )
        emit(ev)
    }

    /**
     * Run [block] and record a timed event.
     *
     * Also wraps the block in an androidx.tracing synchronous section named
     * `"<tag>/<name>"` so Perfetto traces show this phase on the current
     * thread's timeline.
     */
    inline fun <T> measure(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        block: () -> T,
    ): T {
        val sectionName = traceSectionName(tag, name)
        val traced = beginTraceSection(sectionName)
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            event(tag, name, *attrs, durationMs = System.currentTimeMillis() - start)
            result
        } catch (t: Throwable) {
            error(tag, "$name:failed", t, "durationMs" to (System.currentTimeMillis() - start), *attrs)
            throw t
        } finally {
            if (traced) endTraceSection()
        }
    }

    /**
     * Start a manual timer. Call [Timer.stop] when the phase completes.
     *
     * Opens an async tracing section so the phase shows up in Perfetto even
     * when start/stop happen on different threads or coroutines. Each call
     * allocates a unique cookie so concurrent timers with the same name are
     * disambiguated in the trace UI.
     */
    fun startTimer(tag: String, name: String): Timer {
        val sectionName = traceSectionName(tag, name)
        val cookie = traceCookie.incrementAndGet()
        val traced = beginAsyncTraceSection(sectionName, cookie)
        return Timer(
            tag = tag,
            name = name,
            startMs = System.currentTimeMillis(),
            traceSectionName = if (traced) sectionName else null,
            traceCookie = cookie,
        )
    }

    // --- androidx.tracing integration ---------------------------------------

    /** Monotonic cookie source for async tracing sections. */
    @PublishedApi
    internal val traceCookie = AtomicInteger(1)

    /** Perfetto enforces 127 chars max per section name. */
    private const val TRACE_MAX_LEN = 127

    @PublishedApi
    internal fun traceSectionName(tag: String, name: String): String {
        val raw = "$tag/$name"
        return if (raw.length <= TRACE_MAX_LEN) raw else raw.substring(0, TRACE_MAX_LEN)
    }

    /**
     * Begin a synchronous trace section. Returns true if tracing actually
     * started (so callers know whether to pair it with [endTraceSection]).
     *
     * Guarded so unit tests on the JVM (without the Android framework)
     * don't crash; in that environment the tracing calls are no-ops.
     */
    @PublishedApi
    internal fun beginTraceSection(name: String): Boolean = try {
        if (Trace.isEnabled()) {
            Trace.beginSection(name)
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    @PublishedApi
    internal fun endTraceSection() {
        try {
            Trace.endSection()
        } catch (_: Throwable) {
            // Swallow — tracing must never throw into production code paths.
        }
    }

    @PublishedApi
    internal fun beginAsyncTraceSection(name: String, cookie: Int): Boolean = try {
        if (Trace.isEnabled()) {
            Trace.beginAsyncSection(name, cookie)
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    @PublishedApi
    internal fun endAsyncTraceSection(name: String, cookie: Int) {
        try {
            Trace.endAsyncSection(name, cookie)
        } catch (_: Throwable) {
            // Swallow.
        }
    }

    /** Clear the in-memory ring buffer (does not affect Logcat history). */
    fun clear() {
        synchronized(ringLock) {
            ring.clear()
            _eventsFlow.value = emptyList()
        }
    }

    fun snapshot(): List<Event> = _eventsFlow.value

    private fun emit(ev: Event) {
        if (logcatEnabled.get()) {
            logToLogcat(ev)
        }

        // Ring buffer. ConcurrentLinkedDeque makes individual operations safe,
        // but size/trim/snapshot is a compound operation. Keep it atomic so a
        // background emitter cannot race a clear() or another emitter and throw
        // into an unrelated coroutine test.
        synchronized(ringLock) {
            ring.addFirst(ev)
            while (ring.size > MAX_RING_SIZE) ring.pollLast()
            _eventsFlow.value = ring.toList()
        }
    }

    private fun logToLogcat(ev: Event) {
        val tagStr = "$TAG_PREFIX/${ev.tag}"
        val body = buildString {
            append(ev.name)
            if (ev.durationMs != null) append(" (${ev.durationMs}ms)")
            if (ev.attrs.isNotEmpty()) {
                append(" ")
                ev.attrs.entries.joinTo(this, separator = " ") { (k, v) -> "$k=$v" }
            }
        }
        when (ev.level) {
            Level.DEBUG -> Log.d(tagStr, body)
            Level.INFO -> Log.i(tagStr, body)
            Level.WARN -> Log.w(tagStr, body)
            Level.ERROR -> if (ev.throwable != null) Log.e(tagStr, body, ev.throwable) else Log.e(tagStr, body)
        }
    }

    /**
     * Serialize the ring to a human-readable string. For sharing from the
     * dev UI (clipboard / file export).
     */
    fun exportText(): String = buildString {
        append("# Letta Mobile Telemetry Dump (${ring.size} events)\n")
        append("# Generated: ${java.time.Instant.now()}\n\n")
        ring.forEach { ev ->
            append(ev.toLine())
            append('\n')
        }
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Event(
        val timestampMs: Long,
        val tag: String,
        val name: String,
        val durationMs: Long?,
        val attrs: Map<String, Any?>,
        val level: Level,
        val throwable: Throwable? = null,
    ) {
        fun toLine(): String = buildString {
            append(java.time.Instant.ofEpochMilli(timestampMs))
            append(" [").append(level).append("] ")
            append(tag).append('/').append(name)
            if (durationMs != null) append(" (${durationMs}ms)")
            if (attrs.isNotEmpty()) {
                append(" {")
                attrs.entries.joinTo(this, separator = ", ") { (k, v) -> "$k=$v" }
                append("}")
            }
            throwable?.let {
                append("\n  error: ")
                append(it.javaClass.simpleName).append(": ").append(it.message)
            }
        }
    }

    class Timer internal constructor(
        private val tag: String,
        private val name: String,
        private val startMs: Long,
        private val traceSectionName: String?,
        private val traceCookie: Int,
    ) {
        fun stop(vararg attrs: Pair<String, Any?>) {
            closeTraceSection()
            event(tag, name, *attrs, durationMs = System.currentTimeMillis() - startMs)
        }

        fun stopError(throwable: Throwable, vararg attrs: Pair<String, Any?>) {
            closeTraceSection()
            error(
                tag,
                "$name:failed",
                throwable,
                "durationMs" to (System.currentTimeMillis() - startMs),
                *attrs,
            )
        }

        private fun closeTraceSection() {
            traceSectionName?.let { endAsyncTraceSection(it, traceCookie) }
        }
    }
}
