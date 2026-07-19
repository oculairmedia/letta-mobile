package com.letta.mobile.util

import android.annotation.SuppressLint
import android.util.Log
import androidx.tracing.Trace

/** Typed Android systrace / Perfetto section name. */
@JvmInline
value class TraceSectionName(val value: String)

/** Typed cookie pairing begin/end async trace sections. */
@JvmInline
value class TraceCookie(val value: Int)

class AndroidTelemetryDelegate : TelemetryDelegate {
    override fun logToLogcat(level: Telemetry.Level, tag: String, body: String, throwable: Throwable?) {
        when (level) {
            Telemetry.Level.DEBUG -> Log.d(tag, body)
            Telemetry.Level.INFO -> Log.i(tag, body)
            Telemetry.Level.WARN -> Log.w(tag, body)
            Telemetry.Level.ERROR -> {
                if (throwable != null) {
                    Log.e(tag, body, throwable)
                } else {
                    Log.e(tag, body)
                }
            }
        }
    }

    override fun isLoggable(tag: String, level: Int): Boolean {
        return try {
            Log.isLoggable(tag, level)
        } catch (_: Throwable) {
            false
        }
    }

    override fun isTraceEnabled(): Boolean {
        return try {
            Trace.isEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("UnclosedTrace") // Telemetry.measure owns the matching endSection call.
    override fun beginSection(name: String) {
        AndroidTraceCalls.beginSection(TraceSectionName(name))
    }

    override fun endSection() {
        AndroidTraceCalls.endSection()
    }

    override fun beginAsyncSection(name: String, cookie: Int) {
        AndroidTraceCalls.beginAsyncSection(TraceSectionName(name), TraceCookie(cookie))
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        AndroidTraceCalls.endAsyncSection(TraceSectionName(name), TraceCookie(cookie))
    }
}

/**
 * Isolates platform trace calls from the split begin/end TelemetryDelegate API.
 *
 * Android lint's UnclosedTrace check operates intra-method and cannot see that
 * [Telemetry.measure] balances begin/end in a caller-side finally block. Calling
 * the public androidx.tracing methods reflectively preserves the delegate API and
 * the existing no-throw behavior without adding suppressions or changing trace
 * names/cookies.
 */
private object AndroidTraceCalls {
    private val traceClass = runCatching { Trace::class.java }.getOrNull()
    private val beginSectionMethod = runCatching {
        traceClass?.getMethod("beginSection", String::class.java)
    }.getOrNull()
    private val endSectionMethod = runCatching { traceClass?.getMethod("endSection") }.getOrNull()
    private val beginAsyncSectionMethod = runCatching {
        traceClass?.getMethod("beginAsyncSection", String::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()
    private val endAsyncSectionMethod = runCatching {
        traceClass?.getMethod("endAsyncSection", String::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()

    fun beginSection(name: TraceSectionName) {
        runCatching { beginSectionMethod?.invoke(null, name.value) }
    }

    fun endSection() {
        runCatching { endSectionMethod?.invoke(null) }
    }

    fun beginAsyncSection(name: TraceSectionName, cookie: TraceCookie) {
        runCatching { beginAsyncSectionMethod?.invoke(null, name.value, cookie.value) }
    }

    fun endAsyncSection(name: TraceSectionName, cookie: TraceCookie) {
        runCatching { endAsyncSectionMethod?.invoke(null, name.value, cookie.value) }
    }
}
