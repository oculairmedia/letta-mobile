package com.letta.mobile.testutil

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    var apiKey: String? = initialClientModeApiKey

    override val activeConfig: StateFlow<LettaConfig?> = activeConfigState.asStateFlow()

    override fun getActiveConfig(): Flow<LettaConfig?> = activeConfigState

    override fun observeClientModeEnabled(): Flow<Boolean> = clientModeEnabled

    override fun observeClientModeBaseUrl(): Flow<String> = clientModeBaseUrl

    override fun getClientModeApiKey(): String? = apiKey
}
