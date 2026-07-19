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
        try {
            Trace.beginSection(name)
        } catch (_: Throwable) {}
    }

    override fun endSection() {
        try {
            Trace.endSection()
        } catch (_: Throwable) {}
    }

    override fun beginAsyncSection(name: String, cookie: Int) {
        try {
            Trace.beginAsyncSection(name, cookie)
        } catch (_: Throwable) {}
    }

    override fun endAsyncSection(name: String, cookie: Int) {
        try {
            Trace.endAsyncSection(name, cookie)
        } catch (_: Throwable) {}
    }
}
