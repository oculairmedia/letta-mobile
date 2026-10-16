package com.letta.mobile.data.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import com.letta.mobile.data.repository.api.ISettingsRepository
import dagger.Lazy
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Default [SessionRepositoryGraphFactory] for Android.
 *
 * Replaces the former SessionGraphFactory: Hilt injects the assembler and
 * transport binder, and [create] produces a fresh [SessionGraph] generation
 * when [SessionManager] rebuilds on backend change.
 */
@Singleton
class DefaultSessionRepositoryGraphFactory internal constructor(
    private val assembler: SessionGraphAssembler,
    private val channelTransportFactory: SessionChannelTransportFactory,
    private val settingsRepository: ISettingsRepository? = null,
    /**
     * Outer bound for every graph this factory creates, read on each [create]. Production hands
     * back the [ProcessScope] so a graph that is never closed still dies with the process; tests
     * supply their own.
     */
    private val graphParentScope: () -> CoroutineScope,
    /** Where each generation's background cache clear runs; tests pass a deterministic dispatcher. */
    cacheClearContext: CoroutineContext = Dispatchers.IO,
) : SessionRepositoryGraphFactory<SessionGraph> {
    @Inject
    constructor(
        assembler: SessionGraphAssembler,
        channelTransportFactory: SessionChannelTransportFactory,
        settingsRepository: ISettingsRepository,
        // letta-mobile-by4vm.89: injected from :app instead of read off ProcessLifecycleOwner here;
        // Lazy keeps it resolved on first create(), as the old default lambda was.
        @ProcessScope processScope: Lazy<CoroutineScope>,
    ) : this(
        assembler = assembler,
        channelTransportFactory = channelTransportFactory,
        settingsRepository = settingsRepository,
        graphParentScope = processScope::get,
    )

    private val nextId = AtomicLong(0L)

    // letta-mobile-by4vm.94: the clear no longer blocks create() (it used to runBlocking on the
    // caller, which is the main thread when the first graph is built during Hilt injection); the
    // new generation's DAOs wait on its gate instead.
    private val cacheClears = SessionCacheClearChain(cacheClearContext) { assembler.clearCachesForNewSession() }

    override fun create(): SessionGraph {
        val graphId = nextId.incrementAndGet()
        val activeConfig = settingsRepository?.activeConfig?.value
        val descriptor = remoteLettaBackendDescriptor(activeConfig, ANDROID_REMOTE_LETTA_ID_PREFIX)
        // SessionGraph owns and cancels this scope; parenting it means a graph that is never
        // closed dies with its owner rather than outliving everything.
        val parent = graphParentScope()
        val scope = parent + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO
        val channelTransport = channelTransportFactory.create(
            scope = scope,
            activeConfig = activeConfig,
            settingsRepository = settingsRepository,
        )
        // Started last, once nothing above can fail, so a failed create never supersedes the live
        // generation's gate. It runs in the parent (process) scope, not the graph scope: closing
        // this graph on the next switch must not cancel a delete half-way.
        val cacheGate = cacheClears.startGeneration(parent)
        return assembler.assemble(
            SessionGraphAssembleRequest(
                graphId = graphId,
                backendDescriptor = descriptor,
                activeConfig = activeConfig,
                scope = scope,
                channelTransport = channelTransport,
                settingsRepository = settingsRepository,
                cacheGate = cacheGate,
            ),
        )
    }
}
