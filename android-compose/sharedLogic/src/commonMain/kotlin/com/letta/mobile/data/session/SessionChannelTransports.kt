package com.letta.mobile.data.session

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The channel transport of whichever session graph is current: it follows every rebuild, so a client of the host
 * (the shared shell state link) is rebound with the session instead of keeping the transport of a closed one. A graph
 * with no session has an idle transport that is never connected.
 */
fun SessionRepositoryGraphProvider<out SessionRepositoryGraph>.channelTransports(): StateFlow<IChannelTransport> =
    DerivedStateFlow(currentGraph.map { it.channelTransport }) { current.channelTransport }
