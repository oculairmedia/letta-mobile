package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertFalse

class IrohNotebookCapabilityTest {
    @Test
    fun notebookSentinelAloneNeverGrantsAConnection() {
        assertFalse(IrohPeerCapabilities.isAllowed(emptySet(), IrohPeerCapabilities.NOTEBOOK_AUTHENTICATED))
        assertFalse(IrohPeerCapabilities.isAllowed(setOf(IrohPeerCapabilities.ADMIN_FULL), IrohPeerCapabilities.NOTEBOOK_AUTHENTICATED))
    }
}
