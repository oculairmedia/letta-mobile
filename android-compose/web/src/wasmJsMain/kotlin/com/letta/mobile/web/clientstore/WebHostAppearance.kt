package com.letta.mobile.web.clientstore

import com.letta.mobile.data.clientstore.hostAppearanceOver
import com.letta.mobile.data.session.graph.HostSessionGraphProvider
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.hostAppearanceSettings
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The browser's appearance: the person's choice on the host, the same shared client Android and the desktop run, over the
 * live session's channel (rebound with every session) and mirrored in the browser's [store] so a reload while the host is
 * out of reach still draws the last choice. It follows the host on [scope], and [settings] are the rows Settings draws.
 */
internal class WebHostAppearance(
    sessions: HostSessionGraphProvider,
    store: SecureSettingsStore,
    scope: CoroutineScope,
) : HostAppearance by hostAppearanceOver(sessions, store) {
    init {
        scope.launch { run() }
    }

    /** Theme and colour preset; the browser has no wallpaper colours, so no dynamic colour row. */
    val settings: AppearanceSettings = hostAppearanceSettings(this, scope)
}
