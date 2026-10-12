package com.letta.mobile.web

import com.letta.mobile.ui.settings.BackendSettingsActions
import com.letta.mobile.ui.settings.SettingsPageServices
import com.letta.mobile.web.data.WebSettingsStore

/**
 * letta-mobile-ckmub.3.12: what the web's Settings page can do. The backend address and mode are
 * kept in the browser's storage; the access token lasts the tab (see [WebSettingsStore]).
 */
internal fun webSettingsServices(shell: WebShellState, store: WebSettingsStore) = SettingsPageServices(
    backend = BackendSettingsActions(
        onConfigSaved = { saved ->
            shell.config = saved
            store.saveBackend(saved)
        },
        onTokenCleared = { shell.config = shell.config.copy(accessToken = null) },
    ),
)
