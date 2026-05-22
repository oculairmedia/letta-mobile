package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.ISettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun defaultSessionManagerScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Singleton
class SessionManager internal constructor(
    private val settingsRepository: ISettingsRepository,
    private val sessionGraphFactory: SessionGraphFactory,
    private val managerScope: CoroutineScope,
) {
    @Inject
    constructor(
        settingsRepository: ISettingsRepository,
        sessionGraphFactory: SessionGraphFactory,
    ) : this(
        settingsRepository = settingsRepository,
        sessionGraphFactory = sessionGraphFactory,
        managerScope = defaultSessionManagerScope(),
    )

    private val _currentGraph = MutableStateFlow(sessionGraphFactory.create())
    val currentGraph: StateFlow<SessionGraph> = _currentGraph.asStateFlow()

    init {
        managerScope.launch {
            settingsRepository.activeConfigChanges.collect {
                rebuild()
            }
        }
    }

    fun rebuild(): SessionGraph {
        val previous = _currentGraph.value
        val next = sessionGraphFactory.create()
        _currentGraph.value = next
        previous.scope.cancel()
        return next
    }

    val current: SessionGraph
        get() = _currentGraph.value
}
