package com.letta.mobile.data.timeline

actual fun timelineLogger(tag: String): TimelineLogger = BrowserTimelineLogger(tag)

private class BrowserTimelineLogger(
    private val tag: String,
) : TimelineLogger {
    override val isDebugEnabled: Boolean = true

    override fun debug(message: String) {
        println("[$tag] DEBUG: $message")
    }

    override fun warn(message: String, throwable: Throwable?) {
        val suffix = if (throwable != null) ": ${throwable.message}" else ""
        println("[$tag] WARN: $message$suffix")
    }

    override fun error(message: String, throwable: Throwable?) {
        val suffix = if (throwable != null) ": ${throwable.message}" else ""
        println("[$tag] ERROR: $message$suffix")
    }
}
