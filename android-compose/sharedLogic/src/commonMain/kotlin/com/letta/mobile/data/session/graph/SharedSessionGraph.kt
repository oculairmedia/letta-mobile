package com.letta.mobile.data.session.graph

import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.contextTokenReadingsOf
import com.letta.mobile.data.repository.CronRepository
import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope

/**
 * letta-mobile-by4vm.24: the one place a [SessionRepositoryGraph] generation's platform-neutral
 * repositories are built. Android (`SessionGraph` via Hilt's assembler) and desktop
 * (`DesktopRepositoryAdapters`) both call it instead of wiring these by hand, so a repository
 * added here reaches every host at once.
 *
 * Its own package, not `data.session`: it imports `data.repository`, which already imports
 * `data.session`, and the shared layering ratchet forbids the cycle.
 *
 * Hosts still supply what is platform-bound (Room-backed repositories, admin sources, the
 * transport itself); those move here as their implementations reach commonMain.
 */
object SharedSessionGraph {
    /**
     * The repositories that ride the graph's [transport] and live exactly as long as [scope]:
     * cron schedules, the agent's self-todo list, background subagents, and the context chip's
     * token readings (seeded from and saved to [snapshots] when given).
     */
    fun transportBound(
        transport: IChannelTransport,
        scope: CoroutineScope,
        snapshots: ContextReadingSnapshots? = null,
        subagents: SubagentListing = SubagentListing.ACTIVE,
    ): TransportBoundSessionRepositories = TransportBoundSessionRepositories(
        cronRepository = CronRepository(transport = transport, scope = scope),
        selfTodoRepository = SelfTodoRepository(transport = transport, scope = scope),
        subagentRepository = SubagentRepository(
            transport = transport,
            scope = scope,
            includeAll = subagents == SubagentListing.ALL,
        ),
        contextTokenReadings = contextTokenReadingsOf(transport = transport, scope = scope, snapshots = snapshots),
    )
}

/** Which subagents a session asks its host for on refresh. */
enum class SubagentListing {
    /** Running subagents only (mobile). */
    ACTIVE,

    /** Running and recently finished, for a surface that shows a "Finished" section (desktop). */
    ALL,
}

/** What [SharedSessionGraph.transportBound] builds; every member is fed by the graph's transport. */
class TransportBoundSessionRepositories internal constructor(
    val cronRepository: CronRepository,
    val selfTodoRepository: SelfTodoRepository,
    val subagentRepository: SubagentRepository,
    val contextTokenReadings: ContextTokenReadings,
)
