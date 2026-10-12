package com.letta.mobile.di

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.runtime.ApprovalRelayClient
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.runtime.RelayedPermissionModes
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
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

    @Provides
    @Singleton
    fun provideRelayedPermissionModes(
        settings: PermissionModeSettings,
        rpc: AdminRpcInvoker,
        transport: IChannelTransport,
    ): RelayedPermissionModes = RelayedPermissionModes(settings.modes, rpc) { transport.hostCapabilities }
}
