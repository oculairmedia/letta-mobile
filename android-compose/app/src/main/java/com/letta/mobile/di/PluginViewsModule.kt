package com.letta.mobile.di

import android.content.Context
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.plugin.catalog.liveViewsSetting
import com.letta.mobile.data.plugin.remote.PluginHostConnection
import com.letta.mobile.data.plugin.remote.hostPluginViewsModel
import com.letta.mobile.data.plugin.remote.pluginAdminLinkOver
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * letta-mobile-cdgpr.8: the shared plugin views model over the session's channel transport (Iroh
 * admin_rpc), with packages uploaded through the canvas relay. The "Live plugin views" switch is
 * persisted in this device's DataStore preferences. This module only binds; the model is sharedLogic's.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginViewsModule {
    @Provides
    @Singleton
    @Suppress("NoDetachedCoroutineLifecycle") // Process lifetime, like the canvas relay it uploads through.
    fun providePluginViewsModel(
        transport: IChannelTransport,
        relay: CanvasRelayClient,
        settings: ISettingsRepository,
    ): PluginViewsModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return hostPluginViewsModel(
            host = PluginHostConnection(pluginAdminLinkOver { transport }, transport.state),
            relay = relay,
            liveViews = liveViewsSetting(settings, scope),
            scope = scope,
        )
    }
}

/** The app's [PluginViewsModel] for nav-graph composables, which have no view model of their own. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PluginViewsEntryPoint {
    fun pluginViews(): PluginViewsModel
}

fun pluginViewsOf(context: Context): PluginViewsModel =
    EntryPointAccessors.fromApplication(context.applicationContext, PluginViewsEntryPoint::class.java).pluginViews()
