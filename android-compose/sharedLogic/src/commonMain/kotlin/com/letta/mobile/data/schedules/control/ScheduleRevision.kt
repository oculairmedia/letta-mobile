package com.letta.mobile.data.schedules.control

import kotlin.jvm.JvmInline

/** The captured query generation used to discard results after the selected target changes. */
@JvmInline
internal value class ScheduleRevision(val value: Long)

/** The identity selected for deletion, whether it came from native schedules or cron tasks. */
@JvmInline
internal value class ScheduleIdentity(val value: String)
