package com.letta.mobile.data.voice.host.client

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one admin RPC: [result] when it succeeded, else its [error] string. */
class HostVoiceReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The result; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
) {
    override fun toString(): String = "HostVoiceReply(success=$success, error=$error)"
}

/**
 * The part of the host connection the voice endpoint client uses: what the host advertised, one
 * admin RPC, and one streamed admin RPC. The app binds it to its channel transport (an Iroh
 * `admin_rpc` on a bi-stream of its own per request); tests stand in a fake host. This package
 * depends on no transport, so the settings and the voice session can use it.
 */
interface HostVoiceLink {
    /** The feature capabilities the connected host advertised; empty while disconnected. */
    val hostCapabilities: Set<String>

    /** One `admin_rpc` call with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): HostVoiceReply

    /**
     * One streamed `admin_rpc`: every frame of the request's stream in order (`admin_rpc_stream`
     * frames, then the final `admin_rpc_response`). Stopping the collection early cancels the request.
     */
    fun stream(method: String, params: JsonObject): Flow<JsonObject>
}
