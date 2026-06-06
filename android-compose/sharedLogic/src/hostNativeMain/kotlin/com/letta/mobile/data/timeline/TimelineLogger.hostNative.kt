package com.letta.mobile.data.timeline

actual fun timelineLogger(tag: String): TimelineLogger = NoOpTimelineLogger

private object NoOpTimelineLogger : TimelineLogger {
    override val isDebugEnabled: Boolean = false

    override fun debug(message: String) = Unit

    override fun warn(message: String, throwable: Throwable?) = Unit

    override fun error(message: String, throwable: Throwable?) = Unit
}
