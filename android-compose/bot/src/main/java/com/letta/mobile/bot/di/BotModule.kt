package com.letta.mobile.bot.di

import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.bot.channel.NotificationReplyStreamTracker
import com.letta.mobile.bot.context.BatteryContextProvider
import com.letta.mobile.bot.config.BotServerProfileStore
import com.letta.mobile.bot.clientmode.ClientModeController
import com.letta.mobile.bot.clientmode.IClientModeController
import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.chat.IClientModeChatSender
import com.letta.mobile.bot.context.ConnectivityContextProvider
import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.bot.config.IBotServerProfileStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.IBotGateway
import com.letta.mobile.bot.protocol.BotClient
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.bot.context.TimeContextProvider
import com.letta.mobile.bot.runtime.DefaultLettaRuntimeClient
import com.letta.mobile.bot.runtime.LettaRuntimeClient
import com.letta.mobile.bot.tools.AndroidExecutionBridge
import com.letta.mobile.bot.tools.DefaultAndroidExecutionBridge
import com.letta.mobile.bot.tools.DefaultHostToolApprovalEngine
import com.letta.mobile.bot.tools.HostToolApprovalEngine
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
    abstract fun bindLettaRuntimeClient(impl: DefaultLettaRuntimeClient): LettaRuntimeClient

    @Binds
    abstract fun bindAndroidExecutionBridge(impl: DefaultAndroidExecutionBridge): AndroidExecutionBridge

    @Binds
    abstract fun bindHostToolApprovalEngine(impl: DefaultHostToolApprovalEngine): HostToolApprovalEngine

    @Binds
    abstract fun bindBotServerProfileStore(impl: BotServerProfileStore): IBotServerProfileStore

    @Binds
    abstract fun bindBotGateway(impl: BotGateway): IBotGateway

    @Binds
    abstract fun bindClientModeController(impl: ClientModeController): IClientModeController

    @Binds
    abstract fun bindBotClient(impl: InternalBotClient): BotClient

    @Binds
    abstract fun bindClientModeChatSender(impl: ClientModeChatSender): IClientModeChatSender

    @Binds
    abstract fun bindNotificationReplyStreamTracker(
        impl: NotificationReplyHandler,
    ): NotificationReplyStreamTracker

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
