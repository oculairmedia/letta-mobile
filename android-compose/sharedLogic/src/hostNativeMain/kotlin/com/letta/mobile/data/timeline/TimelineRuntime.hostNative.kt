package com.letta.mobile.data.timeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val timelineIoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun isTimelineNetworkFailure(t: Throwable): Boolean = false
