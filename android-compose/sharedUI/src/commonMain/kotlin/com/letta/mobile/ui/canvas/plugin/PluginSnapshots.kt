package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.produceState
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.ui.canvas.CanvasImageAssets
import com.letta.mobile.ui.image.decodeImageBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Where a plugin element's snapshot bytes come from: the board's [assets] first, then [fetch]
 * (the sync transport, which asks the host). Fetched bytes are kept in [assets] so the next look
 * is local. Made once per board, so a card keyed on it never reloads during a pan or zoom.
 */
@Stable
class PluginSnapshotSources(
    val assets: AssetStore?,
    val fetch: suspend (ref: String) -> ByteArray? = { null },
)

/**
 * Hydrates snapshots the way board images are (letta-mobile-w3nb2): read from the asset store off
 * the main thread, else fetched; until the bytes are anywhere, asked again on a backoff, so a
 * snapshot that lands in the store later (a relay catch-up, a peer's upload) replaces the skeleton.
 */
internal object PluginSnapshotHydration {
    const val FIRST_RETRY_MILLIS = 500L
    const val MAX_RETRY_MILLIS = 8_000L

    /** [snapshot] decoded, asking [sources] until its bytes arrive; suspends while they do not. */
    suspend fun load(snapshot: CanvasPluginSnapshot, sources: PluginSnapshotSources): PluginSnapshotImage {
        var wait = FIRST_RETRY_MILLIS
        while (true) {
            val bytes = bytesOf(snapshot, sources)
            if (bytes != null) return decode(bytes)
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    /** The bytes of [snapshot] from the store, else fetched (and then stored), else null. */
    suspend fun bytesOf(snapshot: CanvasPluginSnapshot, sources: PluginSnapshotSources): ByteArray? {
        val store = sources.assets
        val local = store?.let { withContext(Dispatchers.Default) { quietly { it.get(snapshot.assetRef) } } }
        if (local != null) return local
        val fetched = quietlySuspending { sources.fetch(snapshot.assetRef) } ?: return null
        if (store != null) {
            withContext(Dispatchers.Default) {
                quietly { store.put(snapshot.mediaType ?: CanvasImageAssets.sniffMediaType(fetched), fetched) }
            }
        }
        return fetched
    }

    /** [bytes] as an image, or [PluginSnapshotImage.Unreadable] when they are not one this platform decodes. */
    fun decode(bytes: ByteArray): PluginSnapshotImage =
        quietly { decodeImageBitmap(bytes) }?.let { PluginSnapshotImage.Ready(it) } ?: PluginSnapshotImage.Unreadable

    private inline fun <T> quietly(block: () -> T?): T? = runCatching(block).getOrNull()

    private suspend fun <T> quietlySuspending(block: suspend () -> T?): T? =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
}

/** [snapshot] as far as it has arrived: [PluginSnapshotImage.Loading] until its bytes are here. */
@Composable
internal fun rememberPluginSnapshot(snapshot: CanvasPluginSnapshot?, sources: PluginSnapshotSources): PluginSnapshotImage {
    val initial = if (snapshot == null) PluginSnapshotImage.None else PluginSnapshotImage.Loading
    return produceState(initial, snapshot?.assetRef, sources) {
        value = if (snapshot == null) PluginSnapshotImage.None else PluginSnapshotHydration.load(snapshot, sources)
    }.value
}
