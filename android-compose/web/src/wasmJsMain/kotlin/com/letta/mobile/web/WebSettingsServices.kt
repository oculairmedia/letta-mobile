package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.ui.pairing.composition.installAndPairBindings
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebSettingsStore

/**
 * What the web's Settings page can do. The backend address and mode are kept in the browser's storage; the access
 * token lasts the tab (see [WebSettingsStore]). Appearance is the host's, as on desktop; the chat section carries the
 * default permission mode; pairing joins a host by invite through the shared composition.
 */
internal fun webSettingsServices(
    config: MutableState<LettaConfig>,
    store: WebSettingsStore,
    gateway: WasmAppServerClientGateway,
    pairing: WebPairingPorts,
) = SettingsPageServices(
    backend = BackendSettingsActions(
        onConfigSaved = { saved ->
            config.value = saved
            store.saveBackend(saved)
        },
        onTokenCleared = { config.value = config.value.copy(accessToken = null) },
    ),
    appearance = gateway.hostAppearance.settings,
    chat = ChatSettings(permissionMode = gateway.permissionModeSettings),
    pairing = installAndPairBindings(pairing, pairing),
)
