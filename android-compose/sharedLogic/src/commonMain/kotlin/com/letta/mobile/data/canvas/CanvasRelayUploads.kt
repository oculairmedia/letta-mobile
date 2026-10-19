package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.data.storage.AssetRef
import com.letta.mobile.data.storage.AssetRefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Why an asset upload outside a canvas did not reach the host's store; [message] is safe to show. */
class CanvasRelayUploadException(message: String) : Exception(message)

/**
 * Assets put in the host's store outside any canvas (a plugin package before
 * `plugins.install`), on [CanvasRelayProtocol.UPLOAD_TOPIC]. Each upload waits for the host's
 * `asset_stored` or `asset_rejected`. One upload per ref at a time, and anyone asking for the same
 * ref waits on the first.
 */
internal class CanvasRelayUploads {
    private val lock = Mutex()

    /** The host's answer for each ref in flight: empty when stored, else why it was not. */
    private val pending = mutableMapOf<String, CompletableDeferred<String?>>()

    /**
     * Sends [bytes] as [asset] through [send] and waits for the host. Throws [CanvasRelayUploadException]
     * when the host rejects them, the connection ends first, or no answer comes within [timeoutMs].
     */
    suspend fun upload(asset: AssetRef, bytes: ByteArray, timeoutMs: Long, send: suspend (CanvasRelayMessage) -> Unit) {
        val mine = CompletableDeferred<String?>()
        val answer = lock.withLock { pending.getOrPut(asset.ref) { mine } }
        if (answer === mine) sendChunks(asset, bytes, send)
        val reason = withTimeoutOrNull(timeoutMs) { answer.await() } ?: return timedOut(asset, answer)
        if (reason.isNotEmpty()) throw CanvasRelayUploadException("The host did not keep the upload: $reason")
    }

    private suspend fun sendChunks(asset: AssetRef, bytes: ByteArray, send: suspend (CanvasRelayMessage) -> Unit) {
        try {
            forEachAssetChunk(bytes) { index, count, data ->
                send(CanvasRelayMessage.AssetPut(CanvasRelayProtocol.UPLOAD_TOPIC, asset, index, count, data))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            finish(asset.ref, "the upload was cancelled")
            throw e
        } catch (e: Exception) {
            finish(asset.ref, e.message ?: "the connection failed")
        }
    }

    /** No answer in time: a later upload of the same ref starts afresh. */
    private suspend fun timedOut(asset: AssetRef, answer: CompletableDeferred<String?>): Nothing {
        lock.withLock { if (pending[asset.ref] === answer) pending.remove(asset.ref) }
        throw CanvasRelayUploadException("The host did not confirm the upload in time")
    }

    /** The host kept the asset. */
    suspend fun stored(message: CanvasRelayMessage.AssetStored) = finish(message.ref, null)

    /** The host would not keep the asset. */
    suspend fun rejected(message: CanvasRelayMessage.AssetRejected) = finish(message.ref, message.reason)

    /** The connection ended: nothing in flight will be answered on it. */
    suspend fun connectionEnded() {
        val all = lock.withLock { pending.values.toList().also { pending.clear() } }
        all.forEach { it.complete(CONNECTION_ENDED) }
    }

    private suspend fun finish(ref: String, reason: String?) {
        // An empty reason would read as stored; a rejection always says something.
        lock.withLock { pending.remove(ref) }?.complete(reason?.ifEmpty { "rejected" } ?: "")
    }

    companion object {
        const val CONNECTION_ENDED = "the connection to the host ended"

        /**
         * [bytes] as the asset they are, pinned: refused unless they hash to [sha256] (64 lowercase hex),
         * the hash the person was shown.
         */
        fun pinned(mediaType: String, bytes: ByteArray, sha256: String): AssetRef {
            val ref = AssetRefs.PREFIX + sha256
            if (!AssetRefs.isValid(ref)) throw CanvasRelayUploadException("sha256 must be 64 lowercase hex characters")
            val actual = Sha256.digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (actual != sha256) throw CanvasRelayUploadException("The bytes do not match sha256 $sha256")
            return AssetRef(ref, mediaType, bytes.size.toLong())
        }
    }
}
