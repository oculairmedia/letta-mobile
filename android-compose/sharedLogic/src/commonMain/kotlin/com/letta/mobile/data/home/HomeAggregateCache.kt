package com.letta.mobile.data.home

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.session.isUnsupportedHomeStatistic
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One bounded completed result and unsupported figures for the current host/capability epoch. */
internal class HomeAggregateCache(private val clock: Clock) {
    private var completedAt: Instant? = null
    private val unsupported = mutableSetOf<SessionRoute>()

    fun isFresh(): Boolean = completedAt?.let { clock.now() - it in 0.seconds..<60.seconds } == true

    fun complete() {
        completedAt = clock.now()
    }

    fun supports(figure: SessionRoute): Boolean = figure !in unsupported

    fun remember(figure: SessionRoute, failure: Throwable) {
        if (failure.isUnsupportedHomeStatistic()) unsupported += figure
    }
}
