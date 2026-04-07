package com.letta.mobile.di

import com.letta.mobile.data.api.*
import com.letta.mobile.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLettaApiClient(settingsRepository: SettingsRepository): LettaApiClient {
        return LettaApiClient(settingsRepository)
    }

    @Provides
    @Singleton
    fun provideAgentApi(apiClient: LettaApiClient): AgentApi {
        return AgentApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideConversationApi(apiClient: LettaApiClient): ConversationApi {
        return ConversationApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideMessageApi(apiClient: LettaApiClient): MessageApi {
        return MessageApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideBlockApi(apiClient: LettaApiClient): BlockApi {
        return BlockApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideToolApi(apiClient: LettaApiClient): ToolApi {
        return ToolApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideMcpServerApi(apiClient: LettaApiClient): McpServerApi {
        return McpServerApi(apiClient)
    }

    @Provides
    @Singleton
    fun provideModelApi(apiClient: LettaApiClient): ModelApi {
        return ModelApi(apiClient)
    }
}
