package com.letta.mobile.web

import androidx.compose.runtime.MutableState
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.web.data.WebSettingsStore

/**
 * letta-mobile-ckmub.3.12: what the web's Settings page can do. The backend address and mode are
 * kept in the browser's storage; the access token lasts the tab (see [WebSettingsStore]). The chat
 * section carries the default permission mode (letta-mobile-ckmub.17.25), as on desktop.
 */
internal fun webSettingsServices(
    config: MutableState<LettaConfig>,
    store: WebSettingsStore,
    permissionModes: PermissionModeSettings,
) = SettingsPageServices(
    backend = BackendSettingsActions(
        onConfigSaved = { saved ->
            config.value = saved
            store.saveBackend(saved)
        },
        onTokenCleared = { config.value = config.value.copy(accessToken = null) },
    ),
    chat = ChatSettings(permissionMode = permissionModes),
)
