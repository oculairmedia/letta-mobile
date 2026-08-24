package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.api.SlashCommandApi
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import javax.inject.Inject
import javax.inject.Singleton

/** Android binding for [CachedSlashCommandRepository]. Phase 5o. */
@Singleton
class SlashCommandRepository @Inject constructor(
    apiClient: LettaApiClient,
    settingsRepository: ISettingsRepository,
    channelTransport: IChannelTransport,
) : CachedSlashCommandRepository(
    remote = SlashCommandApi(apiClient),
    irohSource = IrohAdminRpcSlashCommandSource(
        channelTransport = channelTransport,
        settingsRepository = settingsRepository,
        deviceId = "android-letta-mobile",
        clientVersion = "android-iroh-admin-rpc",
    ),
)
