package io.ak1.drawbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop

/**
 * Lets a host ask every open board to release decoded images under memory pressure, without the
 * canvas depending on the host's types. A request is a snapshot-state counter rather than a list
 * of callbacks: it is safe to bump from any thread, and each board answers on its own UI thread.
 */
object DrawboxCachePressure {
    internal val requests = mutableLongStateOf(0L)

    /** Every open board drops the decoded images it did not draw in its latest frame. */
    fun dropIdle() {
        requests.longValue++
    }
}

/** A board's image cache that answers [DrawboxCachePressure.dropIdle] for as long as it is composed. */
@Composable
internal fun rememberImageBitmapCache(scope: CoroutineScope): ImageBitmapCache {
    val cache = remember(scope) { ImageBitmapCache(scope) }
    LaunchedEffect(cache) {
        snapshotFlow { DrawboxCachePressure.requests.longValue }.drop(1).collect { cache.dropIdle() }
    }
    return cache
}
