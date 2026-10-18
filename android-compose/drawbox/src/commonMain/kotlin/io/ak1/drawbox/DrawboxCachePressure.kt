package io.ak1.drawbox

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
