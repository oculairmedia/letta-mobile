package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.UsageError
import com.letta.mobile.data.hoststate.HostDirectoryDurability

internal fun requireNotebookHostDurability(supported: Boolean = HostDirectoryDurability.supported()) {
    try {
        HostDirectoryDurability.requireNotebookSharing(supported)
    } catch (unsupported: UnsupportedOperationException) {
        throw UsageError(unsupported.message.orEmpty())
    }
}
