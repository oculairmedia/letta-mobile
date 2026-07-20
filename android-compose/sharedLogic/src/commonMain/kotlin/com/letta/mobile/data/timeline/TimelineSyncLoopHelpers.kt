package com.letta.mobile.data.timeline

internal fun isRetryableReconcileError(t: Throwable): Boolean = when (t) {
    is TimelineTransportHttpException -> t.code in 500..599
    else -> isTimelineNetworkFailure(t)
}

internal fun hydrateRawFetchLimit(visibleTarget: Int): Int =
    (visibleTarget * HYDRATE_RAW_FETCH_MULTIPLIER)
        .coerceIn(visibleTarget, HYDRATE_RAW_FETCH_MAX)

private const val HYDRATE_RAW_FETCH_MULTIPLIER = 5
private const val HYDRATE_RAW_FETCH_MAX = 500
