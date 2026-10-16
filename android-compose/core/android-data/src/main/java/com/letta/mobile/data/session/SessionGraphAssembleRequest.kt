package com.letta.mobile.data.session

import com.letta.mobile.data.local.SessionCacheGate
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope

/** Inputs for one [SessionGraphAssembler.assemble] generation. */
data class SessionGraphAssembleRequest(
    val graphId: Long,
    /** Same snapshot used by the factory for the descriptor and transport. */
    val activeConfig: LettaConfig?,
    val scope: CoroutineScope,
    val channelTransport: IChannelTransport,
    val settingsRepository: ISettingsRepository?,
    /**
     * letta-mobile-by4vm.94: opens once the previous session's Room cache is cleared; this
     * generation's repositories await it before every Room access.
     */
    val cacheGate: SessionCacheGate,
    val backendDescriptor: com.letta.mobile.runtime.BackendDescriptor =
        remoteLettaBackendDescriptor(activeConfig, ANDROID_REMOTE_LETTA_ID_PREFIX),
)
