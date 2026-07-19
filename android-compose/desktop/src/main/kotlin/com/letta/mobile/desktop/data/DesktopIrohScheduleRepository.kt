package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository

class DesktopIrohScheduleRepository(
    directoryProvider: () -> IrohAdminRpcAgentDirectory?,
) : IScheduleRepository by IrohScheduleRepository(directoryProvider)
