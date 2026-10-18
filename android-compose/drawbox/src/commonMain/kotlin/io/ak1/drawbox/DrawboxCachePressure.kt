package io.ak1.drawbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Board caches register here so a shared memory-pressure consumer can drop
 * decoded layers without the canvas depending on Letta types.
 */
object DrawboxCachePressure {
    private val droppers = mutableListOf<() -> Unit>()

    fun register(drop: () -> Unit): () -> Unit {
        droppers += drop
        return { droppers.remove(drop) }
    }

    fun dropAll() {
        droppers.toList().forEach { it() }
    }
}

@Composable
internal fun RememberDrawboxPressure(drop: () -> Unit) {
    DisposableEffect(Unit) {
        val cancel = DrawboxCachePressure.register(drop)
        onDispose(cancel)
    }
}
