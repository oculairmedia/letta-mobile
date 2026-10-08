package com.letta.mobile.di

import com.letta.mobile.data.model.RecentModelsStore
import com.letta.mobile.data.model.SettingsStoreRecentModels
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ConversationModelRepository
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * letta-mobile-w4q4p: App Server provider/model control over the session's
 * channel transport (Iroh admin_rpc). The repositories live in sharedLogic;
 * this module only binds them.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModelControlModule {
    @Provides
    @Singleton
    fun provideModelControlRpc(transport: IChannelTransport): AdminRpcInvoker =
        AdminRpcInvoker.overTransport { transport }

    @Provides
    @Singleton
    fun provideProviderConnectionRepository(rpc: AdminRpcInvoker): ProviderConnectionRepository =
        ProviderConnectionRepository(rpc)

    @Provides
    @Singleton
    fun provideModelCatalogRepository(rpc: AdminRpcInvoker): ModelCatalogRepository = ModelCatalogRepository(rpc)

    @Provides
    @Singleton
    fun provideConversationModelRepository(
        rpc: AdminRpcInvoker,
        recents: RecentModelsStore,
    ): ConversationModelRepository = ConversationModelRepository(rpc, recents = recents)

    /** letta-mobile-bzvro.18: the picker's "Recent" group, kept in the app's settings store. */
    @Provides
    @Singleton
    fun provideRecentModelsStore(store: SecureSettingsStore): RecentModelsStore =
        RecentModelsStore(SettingsStoreRecentModels(store))
}
