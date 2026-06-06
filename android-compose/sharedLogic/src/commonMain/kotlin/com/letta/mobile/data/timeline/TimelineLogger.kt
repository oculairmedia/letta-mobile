package com.letta.mobile.data.timeline

interface TimelineLogger {
    val isDebugEnabled: Boolean

    fun debug(message: String)

    fun warn(message: String, throwable: Throwable? = null)

    fun error(message: String, throwable: Throwable? = null)
}

expect fun timelineLogger(tag: String): TimelineLogger
