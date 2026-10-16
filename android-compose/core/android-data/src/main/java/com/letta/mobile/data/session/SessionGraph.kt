package com.letta.mobile.data.session

import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.runtime.LocalLettaBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * One Android session graph generation: the shared, interface-typed
 * [SharedSessionRepositoryGraph] (letta-mobile-by4vm.86) plus what only Android binds — the
 * generation's [scope], the paging conversation list, the captured config, and a
 * local runtime typed as [LocalLettaBackend].
 */
class SessionGraph internal constructor(
    private val repositories: SharedSessionRepositoryGraph,
    override val localRuntimeBackend: LocalLettaBackend?,
    val scope: CoroutineScope,
    val allConversationsRepository: AllConversationsRepository,
    /** Configuration captured at creation, never resolved from mutable settings. */
    val capturedConfig: LettaConfig? = null,
) : SessionRepositoryGraph by repositories {
    /** letta-mobile-r2zo8: each conversation's latest context total, fed by [channelTransport]. */
    val contextTokenReadings: ContextTokenReadings
        get() = repositories.contextTokenReadings

    override fun close() {
        scope.cancel()
        repositories.close()
    }
}

/**
 * Clears [repository]'s per-backend cache. The graph exposes repositories by interface
 * (letta-mobile-by4vm.86); the Android ones that cache per backend implement [BackendScopedCache].
 */
internal suspend fun <T> clearBackendScopedCache(repository: T) {
    (repository as? BackendScopedCache)?.clearForBackendSwitch()
}
