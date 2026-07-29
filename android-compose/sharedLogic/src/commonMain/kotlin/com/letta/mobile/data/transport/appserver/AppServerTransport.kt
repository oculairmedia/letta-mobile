package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

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

fun AppServerTransport.mergedFrames(): Flow<AppServerReceivedFrame> = channelFlow {
    // Launch children UNDISPATCHED so SharedFlow subscribers attach before the
    // outer attach()/collect returns — merge() launches children with DEFAULT
    // start and can lose the first delta/terminal on zero-replay SharedFlows.
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            controlFrames.collect { send(it) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            streamFrames.collect { send(it) }
        }
    }
}
