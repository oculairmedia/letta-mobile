package com.letta.mobile.testutil

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Hand-written test double for [ISettingsRepository] — see
 * letta-mobile-0dnn for why mockk on the concrete final
 * [com.letta.mobile.data.repository.SettingsRepository] is forbidden
 * in unit tests (StateFlow / EncryptedSharedPreferences state on a
 * final class leaks across the reused daemon JVM).
 *
 * Backing flows are public so tests can either seed initial state via
 * the constructor or mutate it mid-test by calling `.value =` / `.emit`.
 */
class FakeSettingsRepository(
    initialActiveConfig: LettaConfig? = null,
    initialClientModeEnabled: Boolean = false,
    initialClientModeBaseUrl: String = "",
    initialClientModeApiKey: String? = null,
) : ISettingsRepository {

    val activeConfigState: MutableStateFlow<LettaConfig?> =
        MutableStateFlow(initialActiveConfig)

    val clientModeEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(initialClientModeEnabled)

    val clientModeBaseUrl: MutableStateFlow<String> =
        MutableStateFlow(initialClientModeBaseUrl)

    val pinnedProjectIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())

    var apiKey: String? = initialClientModeApiKey

    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()

    override val activeConfigChanges: Flow<LettaConfig> = emptyFlow()

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfigState

    override fun observeClientModeEnabled(): Flow<Boolean> = clientModeEnabled

    override fun observeClientModeBaseUrl(): Flow<String> = clientModeBaseUrl

    override fun getClientModeApiKey(): String? = apiKey

    override fun getPinnedProjectIds(): Flow<Set<String>> = pinnedProjectIds

    override suspend fun setProjectPinned(projectId: String, pinned: Boolean) {
        pinnedProjectIds.value = if (pinned) {
            pinnedProjectIds.value + projectId
        } else {
            pinnedProjectIds.value - projectId
        }
    }
}
