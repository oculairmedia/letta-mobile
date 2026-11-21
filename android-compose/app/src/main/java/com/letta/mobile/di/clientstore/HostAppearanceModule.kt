package com.letta.mobile.di.clientstore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.letta.mobile.data.clientstore.ShellStateCacheStore
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.appearance.SharedShellAppearance
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.storage.DataStoreSettingsPreferencesStore
import com.letta.mobile.data.clientstore.LegacyAppearanceStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The person's appearance on the host (the shell state document), over the session's channel transport. The
 * transport is resolved on first use: it depends on the settings repository this feeds, so asking for it here would
 * be a cycle. The device keeps a disposable mirror of the document and its old local theme choices until migrated.
 */
@Module
@InstallIn(SingletonComponent::class)
object HostAppearanceModule {
    @Provides
    @Singleton
    fun provideHostAppearance(
        dataStore: DataStore<Preferences>,
        secureSettingsStore: SecureSettingsStore,
        transport: Provider<IChannelTransport>,
    ): HostAppearance {
        val mirror = ShellStateCacheStore(secureSettingsStore)
        return SharedShellAppearance(
            shell = SharedShellState(channelShellStateLink { transport.get() }, mirror),
            mirror = mirror,
            legacy = LegacyAppearanceStore(DataStoreSettingsPreferencesStore(dataStore)),
        )
    }
}
