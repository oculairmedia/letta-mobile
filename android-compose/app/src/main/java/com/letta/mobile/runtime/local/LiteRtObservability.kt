package com.letta.mobile.runtime.local

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

const val ON_DEVICE_LITERT_LOG_TAG = "OnDeviceLiteRt"

fun logLiteRtInfo(stage: String, detail: String) {
    logChunked(Log.INFO, ON_DEVICE_LITERT_LOG_TAG, "stage=$stage $detail")
}

fun logLiteRtError(stage: String, detail: String, error: Throwable) {
    logChunked(
        Log.ERROR,
        ON_DEVICE_LITERT_LOG_TAG,
        "stage=$stage $detail error=${error.fullLiteRtErrorText()}",
    )
}

fun Throwable.fullLiteRtErrorText(): String = buildString {
    message?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    append(stackTraceToStringCompat())
}

fun logChunked(priority: Int, tag: String, message: String) {
    if (message.length <= LOGCAT_CHUNK_SIZE) {
        Log.println(priority, tag, message)
        return
    }
    var start = 0
    var part = 1
    val total = (message.length + LOGCAT_CHUNK_SIZE - 1) / LOGCAT_CHUNK_SIZE
    while (start < message.length) {
        val end = (start + LOGCAT_CHUNK_SIZE).coerceAtMost(message.length)
        Log.println(priority, tag, "part=$part/$total ${message.substring(start, end)}")
        start = end
        part += 1
    }
}

private fun Throwable.stackTraceToStringCompat(): String {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.toString()
}

private const val LOGCAT_CHUNK_SIZE = 3500
