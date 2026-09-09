package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.LocalLettaBackend
import kotlinx.coroutines.CoroutineScope

/** Inputs for one [SessionGraphAssembler.assemble] generation. */
data class SessionGraphAssembleRequest(
    val graphId: Long,
    /** Same snapshot used by the factory for the descriptor and transport. */
    val activeConfig: LettaConfig?,
    val localRuntimeBackend: LocalLettaBackend?,
    val scope: CoroutineScope,
    val channelTransport: IChannelTransport,
    val settingsRepository: ISettingsRepository?,
    val backendDescriptor: com.letta.mobile.runtime.BackendDescriptor =
        localRuntimeBackend?.descriptor ?: remoteLettaBackendDescriptor(activeConfig, ANDROID_REMOTE_LETTA_ID_PREFIX),
    val capturedCursorStore: com.letta.mobile.data.local.CapturedBackendConversationCursorStore? = null,
)
