package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.canvas.compose.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The plugin's end of an LCP connection, in Kotlin: the reference a non-Kotlin implementation
 * mirrors and the other half of the host's tests. Register the plugin's methods on [peer] with
 * [serve] before [start]; the session state machine guards both directions as it does on the host.
 */
class LcpPluginSession(transport: LcpTransport, scope: CoroutineScope) {
    private val session = PluginWireSession()
    val peer = LcpPeer(transport, LcpPeerConfig(LcpSide.PLUGIN, session), scope)

    val state: StateFlow<LcpSessionState> get() = session.state

    fun start(): Job = peer.start()

    suspend fun emit(emit: LcpEmit): EmitReceipt = peer.call(LcpCalls.EMIT, emit).receipt

    /** Uploads [bytes] in chunks of at most 1 MiB of base64 and answers the host's ref. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun putAsset(mediaType: String, bytes: ByteArray): String {
        val uploadId = peer.call(LcpCalls.PUT_ASSET_BEGIN, PutAssetBeginParams(mediaType, bytes.size.toLong())).uploadId
        chunksOf(bytes).forEachIndexed { index, chunk ->
            peer.call(LcpCalls.PUT_ASSET_CHUNK, PutAssetChunkParams(uploadId, index, Base64.encode(chunk)))
        }
        return peer.call(LcpCalls.PUT_ASSET_END, PutAssetEndParams(uploadId, LcpAssetUploads.hex(Sha256.digest(bytes)))).ref
    }

    suspend fun readElements(query: LcpElementQuery): List<JsonObject> = peer.call(LcpCalls.READ_ELEMENTS, ReadElementsParams(query)).elements

    suspend fun log(line: LogParams) = peer.notify(LcpCalls.LOG, line)

    fun close() {
        session.close()
        peer.close()
    }

    companion object {
        /** Raw bytes per chunk: their base64 is exactly [LcpWire.MAX_CHUNK_BASE64_CHARS]. */
        const val CHUNK_BYTES: Int = LcpWire.MAX_CHUNK_BASE64_CHARS / 4 * 3

        fun chunksOf(bytes: ByteArray): List<ByteArray> =
            (bytes.indices step CHUNK_BYTES).map { bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size)) }

        /** The error a plugin's `action.invoke` handler throws for its own failure [code]. */
        fun actionFailed(code: String, message: String): LcpCallException =
            LcpCallException(LcpErrorCode.ACTION_FAILED, message, buildJsonObject { put("code", code) })
    }
}
