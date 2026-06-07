package com.letta.mobile.desktop.data

import com.letta.mobile.data.health.IServerHealthRepository
import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class DesktopInMemorySecureSettingsStore(
    initialValues: Map<String, String> = emptyMap(),
) : SecureSettingsStore {
    private val values = initialValues.toMutableMap()

    @Synchronized
    override fun getString(key: String, defaultValue: String?): String? =
        values[key] ?: defaultValue

    @Synchronized
    override fun putString(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    override fun clear() {
        values.clear()
    }
}

class DesktopServerHealthRepository(
    initialStates: Map<String, ServerHealthState> = emptyMap(),
) : IServerHealthRepository {
    private val statesFlow = MutableStateFlow(initialStates)
    override val states: StateFlow<Map<String, ServerHealthState>> = statesFlow

    override suspend fun refreshAll() {
        statesFlow.update { current ->
            current.mapValues { (_, state) ->
                if (state == ServerHealthState.UNKNOWN) ServerHealthState.PROBING else state
            }
        }
    }

    fun setState(configId: String, state: ServerHealthState) {
        statesFlow.update { current -> current + (configId to state) }
    }
}

data class DesktopDataBindings(
    val secureSettingsStore: SecureSettingsStore,
    val healthRepository: IServerHealthRepository,
    val sessionGraphFactory: DesktopSessionGraphFactory,
    val sessionGraphProvider: DesktopSessionGraphProvider,
)

fun createDefaultDesktopDataBindings(): DesktopDataBindings {
    val graphFactory = DesktopSessionGraphFactory()
    return DesktopDataBindings(
        secureSettingsStore = DesktopInMemorySecureSettingsStore(),
        healthRepository = DesktopServerHealthRepository(),
        sessionGraphFactory = graphFactory,
        sessionGraphProvider = DesktopSessionGraphProvider(graphFactory),
    )
}
