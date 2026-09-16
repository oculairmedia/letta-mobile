package com.letta.mobile.data.repository

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.session.BackendSwitchClearResult
import com.letta.mobile.data.session.BackendSwitchInvalidator
import com.letta.mobile.data.storage.DataStoreSettingsPreferencesStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.storage.SettingsPlatformDefaults
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Android binding for [CachedSettingsRepository]: DataStore preferences +
 * encrypted [SecureSettingsStore] + platform defaults.
 *
 * Phase 5q — config routing, pinned-item orchestration, and theme flows live in
 * sharedLogic; this class wires Android storage only.
 */
@Singleton
class SettingsRepository @Inject constructor(
    dataStore: DataStore<Preferences>,
    secureSettingsStore: SecureSettingsStore,
    backendSwitchInvalidator: Provider<BackendSwitchInvalidator>,
) : CachedSettingsRepository(
    preferencesStore = DataStoreSettingsPreferencesStore(dataStore),
    secureSettingsStore = secureSettingsStore,
    platformDefaults = SettingsPlatformDefaults(
        defaultResumeRecentConversation = BuildConfig.DEBUG,
        defaultDynamicColorWhenPresetUnset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    ),
    clearBackendScopedCaches = SettingsBackendCacheClearer {
        backendSwitchInvalidator.get().clearAll().toSettingsResult()
    },
) {
    constructor(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
    ) : this(
        dataStore = dataStore,
        secureSettingsStore = secureSettingsStore,
        backendSwitchInvalidator = Provider { BackendSwitchInvalidator(emptySet()) },
    )

    companion object {
        val DEFAULT_PINNED_SHORTCUTS: List<String> = CachedSettingsRepository.DEFAULT_PINNED_SHORTCUTS

        internal fun forTests(
            dataStore: DataStore<Preferences>,
            secureSettingsStore: SecureSettingsStore,
            clearBackendScopedCaches: suspend () -> BackendSwitchClearResult,
        ): CachedSettingsRepository = CachedSettingsRepository(
            preferencesStore = DataStoreSettingsPreferencesStore(dataStore),
            secureSettingsStore = secureSettingsStore,
            clearBackendScopedCaches = SettingsBackendCacheClearer {
                clearBackendScopedCaches().toSettingsResult()
            },
        )
    }
}

private fun BackendSwitchClearResult.toSettingsResult(): SettingsBackendCacheClearResult =
    SettingsBackendCacheClearResult(
        successes = successes,
        failedCacheNames = failures.map { it.cacheName },
    )
