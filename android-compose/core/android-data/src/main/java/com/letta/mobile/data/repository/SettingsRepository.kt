package com.letta.mobile.data.repository

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.letta.mobile.core.BuildConfig
import com.letta.mobile.data.session.BackendSwitchClearResult
import com.letta.mobile.data.session.BackendSwitchInvalidator
import com.letta.mobile.data.clientstore.LegacyAppearanceStore
import com.letta.mobile.data.clientstore.ShellStateCacheStore
import com.letta.mobile.data.shellstate.appearance.HostAppearance
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.shellstate.shared.channelShellStateLink
import com.letta.mobile.data.storage.DataStoreSettingsPreferencesStore
import com.letta.mobile.data.storage.SettingsPreferencesStore
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
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
    channelTransport: Provider<IChannelTransport>,
) : CachedSettingsRepository(
    preferencesStore = preferences,
    secureSettingsStore = secureSettingsStore.withDevicePins(),
    hostAppearance = hostAppearanceOver(preferences, secureSettingsStore, channelTransport),
    platformDefaults = SettingsPlatformDefaults(
        defaultResumeRecentConversation = BuildConfig.DEBUG,
        defaultDynamicColorWhenPresetUnset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    ),
    clearBackendScopedCaches = SettingsBackendCacheClearer {
        backendSwitchInvalidator.get().clearAll().toSettingsResult()
    },
) {
    /**
     * [channelTransport] is the session's transport, resolved on first use: it depends on the settings that own this
     * repository, so resolving it here would be a cycle. Appearance is read from, and edited on, that transport's host.
     */
    @Inject
    constructor(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
        backendSwitchInvalidator: Provider<BackendSwitchInvalidator>,
        channelTransport: Provider<IChannelTransport>,
    ) : this(
        preferences = DataStoreSettingsPreferencesStore(dataStore),
        secureSettingsStore = secureSettingsStore,
        backendSwitchInvalidator = backendSwitchInvalidator,
        channelTransport = channelTransport,
    )

    /** A repository with no host behind it (tests): appearance shows the defaults and refuses edits. */
    constructor(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
    ) : this(
        dataStore = dataStore,
        secureSettingsStore = secureSettingsStore.withDevicePins(),
        backendSwitchInvalidator = Provider { BackendSwitchInvalidator(emptySet()) },
        channelTransport = Provider { NoOpChannelTransport() },
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

/**
 * The appearance kept on the host of [channelTransport]; this device keeps only a disposable mirror of the host's
 * document (in [secureSettingsStore], the client store's cache) and the old local choices until they are migrated.
 */
private fun hostAppearanceOver(
    preferences: SettingsPreferencesStore,
    secureSettingsStore: SecureSettingsStore,
    channelTransport: Provider<IChannelTransport>,
): HostAppearance {
    val mirror = ShellStateCacheStore(secureSettingsStore)
    return HostAppearance(
        shell = SharedShellState(channelShellStateLink { channelTransport.get() }, mirror),
        mirror = mirror,
        legacy = LegacyAppearanceStore(preferences),
    )
}

private fun BackendSwitchClearResult.toSettingsResult(): SettingsBackendCacheClearResult =
    SettingsBackendCacheClearResult(
        successes = successes,
        failedCacheNames = failures.map { it.cacheName },
    )
