package com.letta.mobile.ui.shell.pages

import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.voice.host.client.HostVoiceLink
import com.letta.mobile.data.voice.host.client.HostVoiceReply
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * The host voice link over the session's channel transport: one Iroh `admin_rpc` per request, and
 * a streamed `admin_rpc` for synthesis, as the provider and model routes are called.
 */
internal fun channelVoiceLink(transport: IChannelTransport): HostVoiceLink = object : HostVoiceLink {
    override val hostCapabilities: Set<String> get() = transport.hostCapabilities

    override suspend fun call(method: String, params: JsonObject): HostVoiceReply {
        val response = transport.adminRpc(method = method, path = "", body = params.toString())
        return HostVoiceReply(response.success, response.result, response.error)
    }

    override fun stream(method: String, params: JsonObject): Flow<JsonObject> = transport.adminRpcStream(method, params)
}
