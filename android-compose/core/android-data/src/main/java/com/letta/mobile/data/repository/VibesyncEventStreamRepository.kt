package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.repository.api.VibesyncEventStreamSource
import com.letta.mobile.data.repository.api.VibesyncEventStreamLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Android binding for [CachedVibesyncEventStreamRepository]. Phase 5o. */
@Singleton
class VibesyncEventStreamRepository internal constructor(
    streamSource: VibesyncEventStreamSource,
    scope: CoroutineScope,
    logger: VibesyncEventStreamLogger,
) : CachedVibesyncEventStreamRepository(
    streamSource = streamSource,
    scope = scope,
    logger = logger,
) {
    @Inject
    constructor(apiClient: LettaApiClient) : this(
        streamSource = LettaHttpVibesyncEventStreamSource(apiClient),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        logger = AndroidVibesyncEventStreamLogger(),
    )
}
