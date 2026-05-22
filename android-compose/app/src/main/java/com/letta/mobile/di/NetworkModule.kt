package com.letta.mobile.di

import android.content.Context
import com.letta.mobile.data.api.*
import com.letta.mobile.data.repository.api.ISettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLettaApiClient(
        @ApplicationContext context: Context,
        settingsRepository: ISettingsRepository
    ): LettaApiClient {
        return LettaApiClient(context, settingsRepository)
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
