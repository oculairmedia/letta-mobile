package com.letta.mobile.data.timeline

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val timelineIoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun isTimelineNetworkFailure(t: Throwable): Boolean = t is IOException
