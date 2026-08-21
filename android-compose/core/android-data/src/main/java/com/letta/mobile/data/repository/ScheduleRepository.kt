package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ScheduleApi

/**
 * Android binding for [CachedScheduleRepository]: HTTP [ScheduleApi].
 *
 * Phase 5d — cache/refresh live in sharedLogic; this type keeps the historical
 * constructor for session wiring and existing unit tests.
 */
open class ScheduleRepository(
    scheduleApi: ScheduleApi,
) : CachedScheduleRepository(
    remote = scheduleApi,
)
