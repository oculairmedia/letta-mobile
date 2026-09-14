package com.letta.mobile.di

import com.letta.mobile.data.presence.ConversationRunRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The app-wide run registry the chat view models feed and the mascots and lists read. */
@Module
@InstallIn(SingletonComponent::class)
object MascotModule {
    @Provides
    @Singleton
    fun provideConversationRunRegistry(): ConversationRunRegistry = ConversationRunRegistry()
}
