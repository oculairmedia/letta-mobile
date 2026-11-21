package com.letta.mobile.desktop.clientstore

import com.letta.mobile.data.clientstore.hostAppearanceOver
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.desktop.data.DesktopPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The desktop's appearance: the person's choice on the host, the same shared client Android and the web run, mirrored in
 * [store] (the desktop's own preferences file) so a cold start with the host out of reach still draws the last choice. The
 * window draws before the session that owns the transport exists, so the shell [attach]es the transport it connects through.
 */
internal class DesktopHostAppearance private constructor(
    private val transport: MutableStateFlow<IChannelTransport?>,
    store: SecureSettingsStore,
) : HostAppearance by hostAppearanceOver(channelShellStateLink(transport), store) {
    constructor(store: SecureSettingsStore) : this(MutableStateFlow(null), store)

    /** Follows the host over [transport] from now on; null while the desktop is not connected to one. */
    fun attach(transport: IChannelTransport?) {
        this.transport.value = transport
    }

    companion object {
        /** The one appearance of the process: every window draws it. */
        val shared: DesktopHostAppearance by lazy { DesktopHostAppearance(DesktopPreferences.store) }
    }
}
