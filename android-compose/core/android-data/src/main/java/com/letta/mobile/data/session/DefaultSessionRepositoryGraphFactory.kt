package com.letta.mobile.data.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventOutbox
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
    private val localRuntimeOptions: LocalRuntimeOptions = LocalRuntimeOptions.Disabled,
    private val cursorFactory: com.letta.mobile.data.local.BackendConversationCursorFactory? = null,
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
        runtimeEventOutbox: RuntimeEventOutbox,
        memFsStore: MemFsStore,
        localRuntimeProviders: Set<@JvmSuppressWildcards LocalRuntimeProvider>,
        settingsRepository: ISettingsRepository,
        cursorFactory: com.letta.mobile.data.local.BackendConversationCursorFactory,
        // letta-mobile-by4vm.89: injected from :app instead of read off ProcessLifecycleOwner here;
        // Lazy keeps it resolved on first create(), as the old default lambda was.
        @ProcessScope processScope: Lazy<CoroutineScope>,
    ) : this(
        assembler = assembler,
        cursorFactory = cursorFactory,
        channelTransportFactory = channelTransportFactory,
        settingsRepository = settingsRepository,
        localRuntimeOptions = LocalRuntimeOptions.Enabled(
            runtimeEventOutbox = runtimeEventOutbox,
            memFsStore = memFsStore,
            providers = localRuntimeProviders,
        ),
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
        val localRuntimeBackend = localRuntimeOptions.createBackend(activeConfig)
        val descriptor = localRuntimeBackend?.descriptor ?: remoteLettaBackendDescriptor(activeConfig, ANDROID_REMOTE_LETTA_ID_PREFIX)
        val cursors = cursorFactory?.capture(descriptor.backendId.value)
        // SessionGraph owns and cancels this scope; parenting it means a graph that is never
        // closed dies with its owner rather than outliving everything.
        val parent = graphParentScope()
        val scope = parent + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO
        val channelTransport = channelTransportFactory.create(
            scope = scope,
            activeConfig = activeConfig,
            localRuntimeBackend = localRuntimeBackend,
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
                capturedCursorStore = cursors,
                activeConfig = activeConfig,
                localRuntimeBackend = localRuntimeBackend,
                scope = scope,
                channelTransport = channelTransport,
                settingsRepository = settingsRepository,
                cacheGate = cacheGate,
            ),
        )
    }
}

internal fun LocalRuntimeOptions.createBackend(config: LettaConfig?): LocalLettaBackend? {
    if (config?.mode != LettaConfig.Mode.LOCAL) {
        return null
    }
    return when (this) {
        LocalRuntimeOptions.Disabled -> null
        is LocalRuntimeOptions.Enabled -> {
            val provider = providers
                .filter { it.supports(config) }
                .maxWithOrNull(compareBy<LocalRuntimeProvider> { it.priority }.thenBy { it.providerId })
                ?: return null
            LocalLettaBackend(
                descriptor = provider.descriptor(config),
                engine = provider.turnEngine(config),
                outbox = runtimeEventOutbox,
                memFsStore = memFsStore,
                onInterrupt = provider::interruptActiveTurn,
            )
        }
    }
}
