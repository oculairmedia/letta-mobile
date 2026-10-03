@file:OptIn(InternalComposeTracingApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import java.util.concurrent.atomic.AtomicInteger

/**
 * letta-mobile-29sxj: counts how often a timeline row's content is composed, by watching the
 * composer's own trace markers for [TimelineItemRow]. A row that skips (its inputs are equal)
 * never starts the function, so a settled row that recomposes per streamed token shows up here
 * without the production rows carrying any test hook.
 */
internal class RowCompositionCounter : AutoCloseable {
    private val count = AtomicInteger()
    private var lastRead = 0

    private val tracer = object : CompositionTracer {
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith(ROW_FUNCTION)) count.incrementAndGet()
        }

        override fun traceEventEnd() = Unit

        override fun isTraceInProgress(): Boolean = true
    }

    init {
        Composer.setTracer(tracer)
    }

    /** Compositions of a row since the previous call. */
    fun takeDelta(): Int {
        val now = count.get()
        return (now - lastRead).also { lastRead = now }
    }

    override fun close() {
        Composer.setTracer(null)
    }

    private companion object {
        /** Trace markers read `<fully.qualified.name> (<file>:<line>)`. */
        const val ROW_FUNCTION = "com.letta.mobile.ui.chat.surface.timeline.TimelineItemRow"
    }
}
