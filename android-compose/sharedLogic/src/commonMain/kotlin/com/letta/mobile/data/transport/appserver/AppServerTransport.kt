package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Transport seam for the App Server WebSocket session.
 *
 * Upstream ≥ 0.29.7 uses one bidirectional socket. [inboundFrames] carries every
 * inbound frame; [controlFrames] / [streamFrames] are per-type views of it, so
 * request correlation (control, `request_id`) and stream observers stay
 * separate. Sends go on the single writable session via [sendControl].
 */
interface AppServerTransport {
    val controlFrames: Flow<AppServerReceivedFrame>
    val streamFrames: Flow<AppServerReceivedFrame>
    val isConnected: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)

    /**
     * Every inbound frame. letta-mobile-by4vm.5: a transport that reads ONE ordered byte
     * stream (the Ktor `/ws` socket) overrides this with the frames in arrival order, so a
     * `turn_finished` is never delivered before the `stream_delta`s the server sent ahead of
     * it, and `event_seq` reaches the gap detector in order. See [AppServerInboundDelivery].
     *
     * The default merges the two lanes and keeps no order between them. It exists for
     * transports whose lanes really are separate streams (Iroh's two QUIC streams, test
     * fakes), where the transport has no cross-lane order to preserve.
     */
    val inboundFrames: Flow<AppServerReceivedFrame> get() = mergedFrames()

    suspend fun sendControl(command: AppServerCommand)
}

/**
 * Both lanes merged with NO order between them; prefer [AppServerTransport.inboundFrames],
 * which is ordered where the transport can order it.
 */
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
