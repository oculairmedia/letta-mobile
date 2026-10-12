package com.letta.mobile.di

import com.letta.mobile.data.chat.approval.ApprovalOutbox
import com.letta.mobile.data.chat.approval.ApprovalOutboxChannel
import com.letta.mobile.data.chat.approval.ApprovalOutboxCoordinator
import com.letta.mobile.data.chat.approval.SettingsApprovalOutboxStore
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.toMessageAnswer
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.runtime.ApprovalRelayClient
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.runtime.RelayedPermissionModes
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.ToolCallId
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * letta-mobile-bzvro.40: the Iroh approval relay and the permission-mode chip on Android. The shared
 * classes live in sharedLogic; this module only binds them to the session's channel transport.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApprovalRelayModule {
    /** The app's one persisted default mode and per-conversation choices. */
    @Provides
    @Singleton
    fun providePermissionModeSettings(store: SecureSettingsStore): PermissionModeSettings = PermissionModeSettings(store)

    /** Folds the host's `pending_approvals` snapshots for the whole process, so no chat screen misses one. */
    @Provides
    @Singleton
    @Suppress("NoDetachedCoroutineLifecycle") // Process lifetime, like the canvas relay client.
    fun provideApprovalRelayClient(transport: IChannelTransport): ApprovalRelayClient =
        ApprovalRelayClient().also { client ->
            client.bind(CoroutineScope(SupervisorJob() + Dispatchers.Default), transport.events)
        }

    /**
     * letta-mobile-bzvro.14: approvals answered while the Iroh link is down wait here (persisted) and
     * are replayed after the reconnect, once per tool call.
     */
    @Provides
    @Singleton
    @Suppress("NoDetachedCoroutineLifecycle") // Process lifetime: a queued answer must outlive any screen.
    fun provideApprovalOutbox(
        transport: IChannelTransport,
        store: SecureSettingsStore,
        relay: ApprovalRelayClient,
        messages: IMessageRepository,
    ): ApprovalOutboxCoordinator = ApprovalOutboxChannel(
        transport = transport,
        pending = relay.pending,
        send = { entry -> messages.submitApproval(entry.toMessageAnswer()) },
        onStale = { entry -> relay.resolve(ToolCallId(entry.key)) },
    ).coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Default), ApprovalOutbox(SettingsApprovalOutboxStore(store)))

    /** letta-mobile-a4tm7: also folds the host's `permission_mode` pushes, so another client's change moves the chip. */
    @Provides
    @Singleton
    @Suppress("NoDetachedCoroutineLifecycle") // Process lifetime, like the approval relay client.
    fun provideRelayedPermissionModes(
        settings: PermissionModeSettings,
        rpc: AdminRpcInvoker,
        transport: IChannelTransport,
    ): RelayedPermissionModes = RelayedPermissionModes(settings.modes, rpc) { transport.hostCapabilities }.also { modes ->
        modes.bind(CoroutineScope(SupervisorJob() + Dispatchers.Default), transport.events)
    }
}
