package com.letta.mobile.desktop.data

import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAgentBlockRepository
import com.letta.mobile.data.repository.iroh.IrohAgentRepository
import com.letta.mobile.data.repository.iroh.IrohScheduleRepository
import com.letta.mobile.data.repository.iroh.IrohToolRepository

internal data class IrohRepositoryBundle(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository,
    val scheduleRepository: IScheduleRepository,
    val toolRepository: IToolRepository,
)

internal fun buildIrohRepositories(
    irohMode: Boolean,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory?,
): IrohRepositoryBundle? {
    if (!irohMode) return null
    return IrohRepositoryBundle(
        agentRepository = IrohAgentRepository(irohAgentDirectoryProvider),
        blockRepository = IrohAgentBlockRepository(irohAgentDirectoryProvider),
        scheduleRepository = IrohScheduleRepository(irohAgentDirectoryProvider),
        toolRepository = IrohToolRepository(irohAgentDirectoryProvider),
    )
}
