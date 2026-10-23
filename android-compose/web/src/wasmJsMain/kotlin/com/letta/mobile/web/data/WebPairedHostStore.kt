package com.letta.mobile.web.data

import androidx.compose.runtime.MutableState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairedHostStore

/**
 * letta-mobile-wisqe S1b: a paired host becomes the web's backend, kept across reloads in
 * [store] and switched to at once through [config]. No credential is kept: the invite was
 * one-time and the browser authenticates by its NodeId from then on.
 */
internal class WebPairedHostStore(
    private val config: MutableState<LettaConfig>,
    private val store: WebSettingsStore,
) : PairedHostStore {
    override suspend fun saveAndActivate(host: PairedHost) {
        val paired = config.value.copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = host.serverUrl, accessToken = null)
        store.saveBackend(paired)
        config.value = paired
    }
}
