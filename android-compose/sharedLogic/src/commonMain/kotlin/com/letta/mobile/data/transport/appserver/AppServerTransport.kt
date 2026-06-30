package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Transport seam for the App Server's dual WebSocket channels.
 *
 * Implementations send commands only on the control channel and receive events
 * from both channels. Request correlation is valid only for control responses
 * that include `request_id`; stream/state events are merged for observation.
 */
interface AppServerTransport {
    val controlFrames: Flow<AppServerReceivedFrame>
    val streamFrames: Flow<AppServerReceivedFrame>
    val isConnected: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)

    suspend fun sendControl(command: AppServerCommand)
}

fun AppServerTransport.mergedFrames(): Flow<AppServerReceivedFrame> =
    merge(controlFrames, streamFrames)
