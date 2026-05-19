package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow surface of [com.letta.mobile.data.repository.SettingsRepository]
 * used by collaborators that need to be test-isolated. Promoted to an
 * interface so tests can substitute a hand-written
 * [com.letta.mobile.testutil.FakeSettingsRepository] instead of mocking
 * the stateful concrete class (mockk on a final class with internal
 * StateFlows / EncryptedSharedPreferences leaks across the daemon JVM —
 * see letta-mobile-0dnn).
 *
 * Members here are limited to what consumers (ShimBackendDetector,
 * ChannelHeartbeatSync, ClientModeController) actually read. The
 * concrete [SettingsRepository] still owns the full configuration
 * surface; widen this interface only when a new consumer needs it.
 */
interface ISettingsRepository {
    val activeConfig: StateFlow<LettaConfig?>
    fun getActiveConfig(): Flow<LettaConfig?>
    fun observeClientModeEnabled(): Flow<Boolean>
    fun observeClientModeBaseUrl(): Flow<String>
    fun getClientModeApiKey(): String?
}
