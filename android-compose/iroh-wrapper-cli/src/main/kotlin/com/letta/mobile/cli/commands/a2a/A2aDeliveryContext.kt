package com.letta.mobile.cli.commands.a2a

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.transport.iroh.IrohAgentMessage

/** Dependencies shared by the inbox routing and creation paths. */
internal class A2aDeliveryContext(
    val store: LocalBackendAdminStore? = null,
    val onAccepted: (IrohAgentMessage, String) -> Unit = { _, _ -> },
)
