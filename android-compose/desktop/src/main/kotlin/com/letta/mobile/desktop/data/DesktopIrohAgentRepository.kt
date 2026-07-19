package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAgentRepository

class DesktopIrohAgentRepository(
    directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IAgentRepository by IrohAgentRepository(directoryProvider)
