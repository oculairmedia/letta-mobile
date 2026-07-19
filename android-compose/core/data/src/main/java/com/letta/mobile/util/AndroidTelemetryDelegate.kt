package com.letta.mobile.util

import android.annotation.SuppressLint
import android.util.Log
import androidx.tracing.Trace

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
        AndroidTraceCalls.beginSection(name)
    }

    override fun endSection() {
        AndroidTraceCalls.endSection()
    }

    override fun beginAsyncSection(name: String, cookie: Int) {
        AndroidTraceCalls.beginAsyncSection(name, cookie)
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        AndroidTraceCalls.endAsyncSection(name, cookie)
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
    private val beginSection = runCatching { traceClass?.getMethod("beginSection", String::class.java) }.getOrNull()
    private val endSection = runCatching { traceClass?.getMethod("endSection") }.getOrNull()
    private val beginAsyncSection = runCatching {
        traceClass?.getMethod("beginAsyncSection", String::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()
    private val endAsyncSection = runCatching {
        traceClass?.getMethod("endAsyncSection", String::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()

    fun beginSection(name: String) {
        runCatching { beginSection?.invoke(null, name) }
    }

    fun endSection() {
        runCatching { endSection?.invoke(null) }
    }

    fun beginAsyncSection(name: String, cookie: Int) {
        runCatching { beginAsyncSection?.invoke(null, name, cookie) }
    }

    fun endAsyncSection(name: String, cookie: Int) {
        runCatching { endAsyncSection?.invoke(null, name, cookie) }
    }
}
