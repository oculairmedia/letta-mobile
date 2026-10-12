package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.VibesyncEventStreamSource
import com.letta.mobile.data.repository.api.VibesyncEventStreamLogger
import com.letta.mobile.data.repository.api.VibesyncStreamEndpointUnavailableException
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope

/**
 * Android binding for [CachedVibesyncEventStreamRepository]. letta-mobile-ckmub.4: the VibeSync
 * event stream was read over the Letta REST client (`/api/events/stream`), which is gone; no App
 * Server or Iroh route carries it yet, so production reports the endpoint as unavailable.
 */
class VibesyncEventStreamRepository internal constructor(
    streamSource: VibesyncEventStreamSource,
    scope: CoroutineScope,
    logger: VibesyncEventStreamLogger,
) : CachedVibesyncEventStreamRepository(
    streamSource = streamSource,
    scope = scope,
    logger = logger,
) {
    /** A repository on [scope] (a session graph's) whose stream reports the endpoint unavailable. */
    constructor(scope: CoroutineScope) : this(
        streamSource = NoVibesyncEventStream,
        scope = scope,
        logger = AndroidVibesyncEventStreamLogger(),
    )
}

/** No backend carries the VibeSync event stream since the Letta REST client was removed. */
private object NoVibesyncEventStream : VibesyncEventStreamSource {
    override suspend fun openStream(): ByteReadChannel = throw VibesyncStreamEndpointUnavailableException()
}
