package com.letta.mobile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.session.BackendSwitchClearResult
import com.letta.mobile.data.session.BackendSwitchInvalidator
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.storage.SettingsPreferencesStore
import com.letta.mobile.data.storage.DataStoreSettingsPreferencesStore
import com.letta.mobile.data.objectverbs.binding.withDevicePins
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
class SettingsRepository private constructor(
    preferences: SettingsPreferencesStore,
    secureSettingsStore: SecureSettingsStore,
    backendSwitchInvalidator: Provider<BackendSwitchInvalidator>,
    hostAppearance: HostAppearance,
) : CachedSettingsRepository(
    preferencesStore = preferences,
    secureSettingsStore = secureSettingsStore.withDevicePins(),
    hostAppearance = hostAppearance,
    platformDefaults = SettingsPlatformDefaults(
        defaultResumeRecentConversation = BuildConfig.DEBUG,
        // The Baken palette is the default on every platform; wallpaper colours (Material You) are opt-in in
        // Settings, so Android draws the same brand colours (and ambient glow) as desktop and the web.
        defaultDynamicColorWhenPresetUnset = false,
    ),
    clearBackendScopedCaches = SettingsBackendCacheClearer {
        backendSwitchInvalidator.get().clearAll().toSettingsResult()
    },
) {
    /**
     * [hostAppearance] is the person's appearance on the host (the shell state document); the app binds it with the
     * session's transport resolved lazily, so this repository does not depend on the transport it feeds.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
        backendSwitchInvalidator: Provider<BackendSwitchInvalidator>,
        hostAppearance: HostAppearance,
    ) : this(
        preferences = DataStoreSettingsPreferencesStore(dataStore),
        secureSettingsStore = secureSettingsStore,
        backendSwitchInvalidator = backendSwitchInvalidator,
        hostAppearance = hostAppearance,
    )

    /** A repository with no host behind it (tests): appearance shows the defaults and refuses edits. */
    constructor(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
    ) : this(
        dataStore = dataStore,
        secureSettingsStore = secureSettingsStore.withDevicePins(),
        backendSwitchInvalidator = Provider { BackendSwitchInvalidator(emptySet()) },
        hostAppearance = HostAppearance.None,
    )

    companion object {
        internal fun forTests(
            dataStore: DataStore<Preferences>,
            secureSettingsStore: SecureSettingsStore,
            clearBackendScopedCaches: suspend () -> BackendSwitchClearResult,
        ): CachedSettingsRepository = CachedSettingsRepository(
            preferencesStore = DataStoreSettingsPreferencesStore(dataStore),
            secureSettingsStore = secureSettingsStore.withDevicePins(),
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
