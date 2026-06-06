package com.letta.mobile.data.timeline

import kotlinx.coroutines.CoroutineDispatcher

expect val timelineIoDispatcher: CoroutineDispatcher

expect fun isTimelineNetworkFailure(t: Throwable): Boolean
