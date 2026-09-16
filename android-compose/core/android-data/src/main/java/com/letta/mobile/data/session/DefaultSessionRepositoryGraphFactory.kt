package com.letta.mobile.data.session

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventOutbox
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

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
     * Outer bound for every graph this factory creates. Production hands back the process
     * lifecycle so a graph that is never closed still dies with the process; tests supply their
     * own so the factory does not reach for a main-thread global to build a data-layer object.
     */
    private val graphParentScope: () -> CoroutineScope = { ProcessLifecycleOwner.get().lifecycleScope },
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
    )

    private val nextId = AtomicLong(0L)

    override fun create(): SessionGraph {
        val graphId = nextId.incrementAndGet()
        val activeConfig = settingsRepository?.activeConfig?.value
        val localRuntimeBackend = localRuntimeOptions.createBackend(activeConfig)
        val descriptor = localRuntimeBackend?.descriptor ?: remoteLettaBackendDescriptor(activeConfig, ANDROID_REMOTE_LETTA_ID_PREFIX)
        val cursors = cursorFactory?.capture(descriptor.backendId.value)
        runBlocking(Dispatchers.IO) {
            assembler.clearCachesForNewSession()
        }
        // SessionGraph owns and cancels this scope; parenting it means a graph that is never
        // closed dies with its owner rather than outliving everything.
        val parent = graphParentScope()
        val scope = parent + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO
        val channelTransport = channelTransportFactory.create(
            scope = scope,
            activeConfig = activeConfig,
            localRuntimeBackend = localRuntimeBackend,
            settingsRepository = settingsRepository,
            capturedCursorStore = cursors,
        )
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
