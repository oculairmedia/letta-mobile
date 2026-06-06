package com.letta.mobile.data.timeline

import android.util.Log

actual fun timelineLogger(tag: String): TimelineLogger = AndroidTimelineLogger(tag)

private class AndroidTimelineLogger(
    private val tag: String,
) : TimelineLogger {
    override val isDebugEnabled: Boolean
        get() = Log.isLoggable(tag, Log.DEBUG)

    override fun debug(message: String) {
        Log.d(tag, message)
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, throwable)
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }
}
