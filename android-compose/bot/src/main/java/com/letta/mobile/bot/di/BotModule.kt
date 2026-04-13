package com.letta.mobile.bot.di

import com.letta.mobile.bot.context.BatteryContextProvider
import com.letta.mobile.bot.context.ConnectivityContextProvider
import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.bot.context.TimeContextProvider
import com.letta.mobile.bot.tools.AndroidExecutionBridge
import com.letta.mobile.bot.tools.DefaultAndroidExecutionBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module for the bot subsystem.
 *
 * Provides:
 * - Device context providers (battery, connectivity, time) as a multibinding set
 * - Session factories are provided by @AssistedFactory on the sessions themselves
 * - BotGateway, BotConfigStore, channel adapters are @Singleton @Inject constructors
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BotModule {

    @Binds
    abstract fun bindAndroidExecutionBridge(impl: DefaultAndroidExecutionBridge): AndroidExecutionBridge

    @Binds
    @IntoSet
    abstract fun bindBatteryProvider(impl: BatteryContextProvider): DeviceContextProvider

    @Binds
    @IntoSet
    abstract fun bindConnectivityProvider(impl: ConnectivityContextProvider): DeviceContextProvider

    @Binds
    @IntoSet
    abstract fun bindTimeProvider(impl: TimeContextProvider): DeviceContextProvider
}
