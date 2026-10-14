package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.repository.api.SlashCommandIrohSource
import com.letta.mobile.data.repository.api.SlashCommandRemoteSource
import kotlinx.coroutines.CancellationException

/** Phase 5o: platform-neutral slash-command repository with HTTP/Iroh routing. */
open class CachedSlashCommandRepository(
    private val remote: RemoteRoute<SlashCommandRemoteSource>,
    private val irohSource: SlashCommandIrohSource? = null,
) : ISlashCommandRepository {
    override suspend fun listGlobal(): Result<List<SlashCommand>> = runRepo {
        fromActiveSource(
            iroh = { it.listGlobal() },
            http = { remote.source().listGlobal() },
        )
    }

    override suspend fun listForAgent(agentId: String): Result<List<SlashCommand>> = runRepo {
        fromActiveSource(
            iroh = { it.listForAgent(agentId) },
            http = { remote.source().listForAgent(agentId) },
        )
    }

    override suspend fun installToAgent(agentId: String, skillName: String): Result<Unit> = runRepo {
        fromActiveSource(
            iroh = {
                it.installToAgent(agentId, skillName)
            },
            http = {
                remote.source().installToAgent(agentId, skillName)
            },
        )
    }

    override suspend fun uninstallFromAgent(agentId: String, skillName: String): Result<Unit> = runRepo {
        fromActiveSource(
            iroh = {
                it.uninstallFromAgent(agentId, skillName)
            },
            http = {
                remote.source().uninstallFromAgent(agentId, skillName)
            },
        )
    }

    override suspend fun getGoalStatus(agentId: String): Result<GoalStatusResponse> = runRepo {
        fromActiveSource(
            iroh = { it.getGoalStatus(agentId) },
            http = { remote.source().getGoalStatus(agentId) },
        )
    }

    override suspend fun executeGoalCommand(agentId: String, command: String): Result<String> = runRepo {
        fromActiveSource(
            iroh = { it.executeGoalCommand(agentId, command) },
            http = { remote.source().executeGoalCommand(agentId, command) },
        )
    }

    private suspend fun <T> fromActiveSource(
        iroh: suspend (SlashCommandIrohSource) -> T,
        http: suspend () -> T,
    ): T {
        val source = irohSource
        return if (source != null && source.shouldUseIroh()) iroh(source) else http()
    }

    private suspend fun <T> runRepo(block: suspend () -> T): Result<T> =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }
}
