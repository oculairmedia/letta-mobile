package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

interface AppServerTransport {
    val controlFrames: Flow<AppServerReceivedFrame>
    val streamFrames: Flow<AppServerReceivedFrame>

    suspend fun sendControl(command: AppServerCommand)
}

fun AppServerTransport.mergedFrames(): Flow<AppServerReceivedFrame> =
    merge(controlFrames, streamFrames)
