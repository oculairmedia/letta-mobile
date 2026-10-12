package com.letta.mobile.di

import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.notify.StoredAttentionPrefs
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** letta-mobile-bzvro.27: per-agent notification mutes, kept in the app's settings store. */
@Module
@InstallIn(SingletonComponent::class)
object AttentionModule {
    @Provides
    @Singleton
    fun provideAttentionPrefs(store: SecureSettingsStore): AttentionPrefsRepository = StoredAttentionPrefs(store)
}
