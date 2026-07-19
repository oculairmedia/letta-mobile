package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohToolRepository

class DesktopIrohToolRepository(
    directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IToolRepository by IrohToolRepository(directoryProvider)
