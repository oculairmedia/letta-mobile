package com.letta.mobile.di

import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.runtime.StoredAgentDefaults
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * letta-mobile-k5nbq.9 / k5nbq.12: the one per-agent defaults store, kept in the app's settings
 * store. The agent editor and the turn engine read the same instance, so a saved default cwd is
 * the one a new conversation starts in.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentDefaultsModule {
    @Provides
    @Singleton
    fun provideAgentDefaults(store: SecureSettingsStore): AgentDefaultsRepository = StoredAgentDefaults(store)
}
