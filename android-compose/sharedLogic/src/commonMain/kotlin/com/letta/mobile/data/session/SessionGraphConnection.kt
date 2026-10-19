package com.letta.mobile.data.session

import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Session readiness for page data sources, without exposing transport states to those consumers. */
internal fun SessionRepositoryGraph.connectionReadiness(): Flow<Boolean> =
    channelTransport.state.map { it is ChannelTransportState.Connected }.distinctUntilChanged()
