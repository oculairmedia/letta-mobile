package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsSource

/** letta-mobile-bzvro.24: the MemFS browser's source on the desktop's direct App Server session. */
internal fun DesktopWorkspaceSources.memfs(): MemfsSource =
    AppServerMemfsSource(client = ::client, events = events, requestId = ::requestId)
