package com.letta.mobile.data.session

import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun defaultSessionManagerScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Android session graph provider: shared rebuild semantics from
 * [DefaultSessionRepositoryGraphProvider], plus auto-rebuild when the active
 * backend connection key changes (including same-id local model edits).
 */
@Singleton
class SessionManager internal constructor(
    private val settingsRepository: ISettingsRepository,
    sessionGraphFactory: SessionRepositoryGraphFactory<SessionGraph>,
    private val managerScope: CoroutineScope,
) : DefaultSessionRepositoryGraphProvider<SessionGraph>(sessionGraphFactory) {
    @Inject
    constructor(
        settingsRepository: ISettingsRepository,
        sessionGraphFactory: DefaultSessionRepositoryGraphFactory,
    ) : this(
        settingsRepository = settingsRepository,
        sessionGraphFactory = sessionGraphFactory,
        managerScope = defaultSessionManagerScope(),
    )

    private var graphBackendKey: BackendConnectionKey? =
        settingsRepository.activeConfig.value?.backendConnectionKey()

    init {
        managerScope.launch {
            // letta-mobile-mlyhq: raw activeConfig, not id-distinct
            // activeConfigChanges — selecting an embedded local model edits the
            // SAME config id, which the id-distinct flow never emits.
            settingsRepository.activeConfig.collect { config ->
                try {
                    if (config != null) rebuildIfBackendChanged(config)
                } catch (t: Throwable) {
                    Log.e("SessionManager", "Failed to auto-rebuild session graph on config change", t)
                }
            }
        }
    }

    private fun rebuildIfBackendChanged(config: LettaConfig) {
        val nextKey = config.backendConnectionKey()
        if (nextKey == graphBackendKey) return
        rebuildTrackingKey(nextKey)
    }

    override fun rebuild(): SessionGraph =
        rebuildTrackingKey(settingsRepository.activeConfig.value?.backendConnectionKey())

    private fun rebuildTrackingKey(nextKey: BackendConnectionKey?): SessionGraph {
        val next = super.rebuild()
        graphBackendKey = nextKey
        return next
    }
}
