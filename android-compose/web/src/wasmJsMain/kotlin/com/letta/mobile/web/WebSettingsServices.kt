package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.web.data.WebSettingsStore

/**
 * letta-mobile-ckmub.3.12: what the web's Settings page can do. The backend address and mode are
 * kept in the browser's storage; the access token lasts the tab (see [WebSettingsStore]).
 */
internal fun webSettingsServices(config: MutableState<LettaConfig>, store: WebSettingsStore) = SettingsPageServices(
    backend = BackendSettingsActions(
        onConfigSaved = { saved ->
            config.value = saved
            store.saveBackend(saved)
        },
        onTokenCleared = { config.value = config.value.copy(accessToken = null) },
    ),
)
