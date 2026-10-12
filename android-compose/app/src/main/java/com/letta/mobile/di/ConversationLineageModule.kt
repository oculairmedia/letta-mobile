package com.letta.mobile.di

import com.letta.mobile.data.chat.branch.ConversationLineage
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * letta-mobile-7mys7 (G37): which conversation each fork came from, kept in the app's settings
 * store. The chat records a fork's parent when it makes one; the drawer reads it for "Forked from".
 */
@Module
@InstallIn(SingletonComponent::class)
object ConversationLineageModule {
    @Provides
    @Singleton
    fun provideConversationLineage(store: SecureSettingsStore): ConversationLineage = ConversationLineage(store)
}
