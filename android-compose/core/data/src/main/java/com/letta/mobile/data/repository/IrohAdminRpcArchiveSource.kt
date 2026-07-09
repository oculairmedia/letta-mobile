package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class IrohAdminRpcArchiveSource(
    channelTransport: IChannelTransport,
    settingsRepository: ISettingsRepository,
) {
    private val client = IrohAdminRpcClient(channelTransport, settingsRepository)

    fun shouldUseIroh(): Boolean =
        client.shouldUseIroh()

    suspend fun listArchives(): List<Archive> {
        return client.callList<Archive>("archive.list", "/v1/archives", "{}")
    }
}
