package com.letta.mobile.desktop.channels

import com.letta.mobile.data.channel.ChannelLibraryController
import com.letta.mobile.data.channel.ChannelLibraryState
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

typealias DesktopChannelLibraryState = ChannelLibraryState

class DesktopChannelLibraryController(
    sessionGraphProvider: DesktopSessionGraphProvider,
    scope: CoroutineScope,
) : AutoCloseable {
    private val delegate = ChannelLibraryController(
        sessionGraphProvider = sessionGraphProvider,
        scope = scope,
    )
    val state: StateFlow<DesktopChannelLibraryState> = delegate.state

    fun start() {
        delegate.start()
    }

    fun refresh() {
        delegate.refresh()
    }

    override fun close() {
        delegate.close()
    }
}
