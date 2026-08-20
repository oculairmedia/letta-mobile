package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.LocalLettaBackend
import kotlinx.coroutines.CoroutineScope

/** Inputs for one [SessionGraphAssembler.assemble] generation. */
data class SessionGraphAssembleRequest(
    val graphId: Long,
    val activeConfig: LettaConfig?,
    val localRuntimeBackend: LocalLettaBackend?,
    val scope: CoroutineScope,
    val channelTransport: IChannelTransport,
    val settingsRepository: ISettingsRepository?,
)
