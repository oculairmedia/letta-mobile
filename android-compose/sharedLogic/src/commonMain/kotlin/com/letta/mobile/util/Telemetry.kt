package com.letta.mobile.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

interface TelemetryDelegate {
    fun logToLogcat(level: Telemetry.Level, tag: String, body: String, throwable: Throwable? = null)
    fun isLoggable(tag: String, level: Int): Boolean
    fun isTraceEnabled(): Boolean
    fun beginSection(name: String)
    fun endSection()
    fun beginAsyncSection(name: String, cookie: Int)
    fun endAsyncSection(name: String, cookie: Int)
}

class TelemetryFlag(initialValue: Boolean) {
    private val state = MutableStateFlow(initialValue)

    fun get(): Boolean = state.value

    fun set(value: Boolean) {
        state.value = value
    }
}

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

object Telemetry {
    var delegate: TelemetryDelegate? = null

    @Suppress("MemberVisibilityCanBePrivate")
    val enabled = TelemetryFlag(true)

    @Suppress("MemberVisibilityCanBePrivate")
    val logcatEnabled = TelemetryFlag(true)

    @Suppress("MemberVisibilityCanBePrivate")
    val timelineDumpEnabled = TelemetryFlag(false)

    @Suppress("MemberVisibilityCanBePrivate")
    val chatHotPathDebugEnabled = TelemetryFlag(false)

    private const val TIMELINE_DUMP_TAG = "LettaTimelineDump"
    private const val CHAT_HOT_PATH_DEBUG_TAG = "LettaChatHotPath"
    private const val TAG_PREFIX = "Telemetry"
    private const val MAX_RING_SIZE = 1000
    private const val TRACE_MAX_LEN = 127

    private val _eventsFlow = MutableStateFlow<List<Event>>(emptyList())
    private var traceCookie = 1

    val events: StateFlow<List<Event>> = _eventsFlow.asStateFlow()

    fun isTimelineDumpEnabled(): Boolean =
        timelineDumpEnabled.get() || (delegate?.isLoggable(TIMELINE_DUMP_TAG, 2) ?: false)

    fun isChatHotPathDebugEnabled(): Boolean =
        chatHotPathDebugEnabled.get() || (delegate?.isLoggable(CHAT_HOT_PATH_DEBUG_TAG, 2) ?: false)

    fun event(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        durationMs: Long? = null,
        level: Level = Level.INFO,
    ) {
        if (!enabled.get()) return

        emit(
            Event(
                timestampMs = nowEpochMillis(),
                tag = tag,
                name = name,
                durationMs = durationMs,
                attrs = attrs.toMap(),
                level = level,
            )
        )
    }

    fun error(
        tag: String,
        name: String,
        throwable: Throwable,
        vararg attrs: Pair<String, Any?>,
    ) {
        if (!enabled.get()) return

        val allAttrs = attrs.toMutableList()
        allAttrs.add("errorClass" to (throwable::class.simpleName ?: "Throwable"))
        allAttrs.add("errorMessage" to (throwable.message ?: "<none>"))
        emit(
            Event(
                timestampMs = nowEpochMillis(),
                tag = tag,
                name = name,
                durationMs = null,
                attrs = allAttrs.toMap(),
                level = Level.ERROR,
                throwable = throwable,
            )
        )
    }

    inline fun <T> measure(
        tag: String,
        name: String,
        vararg attrs: Pair<String, Any?>,
        block: () -> T,
    ): T {
        val sectionName = traceSectionName(tag, name)
        val traced = beginTraceSection(sectionName)
        val start = nowEpochMillis()
        return try {
            val result = block()
            event(tag, name, *attrs, durationMs = nowEpochMillis() - start)
            result
        } catch (t: Throwable) {
            error(tag, "$name:failed", t, "durationMs" to (nowEpochMillis() - start), *attrs)
            throw t
        } finally {
            if (traced) endTraceSection()
        }
    }

    fun startTimer(tag: String, name: String): Timer {
        val sectionName = traceSectionName(tag, name)
        val cookie = nextTraceCookie()
        val traced = beginAsyncTraceSection(sectionName, cookie)
        return Timer(
            tag = tag,
            name = name,
            startMs = nowEpochMillis(),
            traceSectionName = if (traced) sectionName else null,
            traceCookie = cookie,
        )
    }

    @PublishedApi
    internal fun traceSectionName(tag: String, name: String): String {
        val raw = "$tag/$name"
        return if (raw.length <= TRACE_MAX_LEN) raw else raw.substring(0, TRACE_MAX_LEN)
    }

    @PublishedApi
    internal fun beginTraceSection(name: String): Boolean = try {
        val currentDelegate = delegate
        if (currentDelegate != null && currentDelegate.isTraceEnabled()) {
            currentDelegate.beginSection(name)
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
            delegate?.endSection()
        } catch (_: Throwable) {
        }
    }

    @PublishedApi
    internal fun beginAsyncTraceSection(name: String, cookie: Int): Boolean = try {
        val currentDelegate = delegate
        if (currentDelegate != null && currentDelegate.isTraceEnabled()) {
            currentDelegate.beginAsyncSection(name, cookie)
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
            delegate?.endAsyncSection(name, cookie)
        } catch (_: Throwable) {
        }
    }

    fun clear() {
        _eventsFlow.value = emptyList()
    }

    fun snapshot(): List<Event> = _eventsFlow.value

    private fun emit(ev: Event) {
        if (logcatEnabled.get()) {
            logToLogcat(ev)
        }

        _eventsFlow.update { current -> (listOf(ev) + current).take(MAX_RING_SIZE) }
    }

    private fun logToLogcat(ev: Event) {
        val tagStr = "$TAG_PREFIX/${ev.tag}"
        val body = buildString {
            append(ev.name)
            if (ev.durationMs != null) append(" (${ev.durationMs}ms)")
            if (ev.attrs.isNotEmpty()) {
                append(" ")
                ev.attrs.entries.joinTo(this, separator = " ") { (key, value) -> "$key=$value" }
            }
        }
        val currentDelegate = delegate
        if (currentDelegate != null) {
            currentDelegate.logToLogcat(ev.level, tagStr, body, ev.throwable)
        } else {
            val prefix = when (ev.level) {
                Level.DEBUG -> "[DEBUG]"
                Level.INFO -> "[INFO]"
                Level.WARN -> "[WARN]"
                Level.ERROR -> "[ERROR]"
            }
            println("$prefix $tagStr: $body")
        }
    }

    fun exportText(): String = buildString {
        val ring = snapshot()
        append("# Letta Mobile Telemetry Dump (${ring.size} events)\n")
        append("# Generated: ${Clock.System.now()}\n\n")
        ring.forEach { ev ->
            append(ev.toLine())
            append('\n')
        }
    }

    private fun nextTraceCookie(): Int {
        traceCookie += 1
        return traceCookie
    }

    @PublishedApi
    internal fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

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
            append(timestampMs)
            append(" [").append(level).append("] ")
            append(tag).append('/').append(name)
            if (durationMs != null) append(" (${durationMs}ms)")
            if (attrs.isNotEmpty()) {
                append(" {")
                attrs.entries.joinTo(this, separator = ", ") { (key, value) -> "$key=$value" }
                append("}")
            }
            throwable?.let {
                append("\n  error: ")
                append(it::class.simpleName ?: "Throwable").append(": ").append(it.message)
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
            event(tag, name, *attrs, durationMs = nowEpochMillis() - startMs)
        }

        fun stopError(throwable: Throwable, vararg attrs: Pair<String, Any?>) {
            closeTraceSection()
            error(
                tag,
                "$name:failed",
                throwable,
                "durationMs" to (nowEpochMillis() - startMs),
                *attrs,
            )
        }

        private fun closeTraceSection() {
            traceSectionName?.let { endAsyncTraceSection(it, traceCookie) }
        }
    }
}
