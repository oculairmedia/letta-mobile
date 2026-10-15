package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedSlashCommandRepository]: slash commands over an Iroh host.
 * letta-mobile-ckmub.4: the Letta REST source is gone, so the remote seam binds no route.
 */
@Singleton
class SlashCommandRepository @Inject constructor(
    settingsRepository: ISettingsRepository,
    channelTransport: IChannelTransport,
) : CachedSlashCommandRepository(
    remote = RemoteRoute.Absent(SessionRoute.SKILLS),
    irohSource = IrohAdminRpcSlashCommandSource(
        channelTransport = channelTransport,
        settingsRepository = settingsRepository,
        deviceId = "android-letta-mobile",
        clientVersion = "android-iroh-admin-rpc",
    ),
)
