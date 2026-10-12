package io.ak1.drawbox

/**
 * Counts of the drawing work DrawBox does, for benchmarks and performance tests: a pan inside the
 * cached region should draw no element, a big board should record only what is near the view.
 * Process-wide and unsynchronised (it is written from the draw pass); read it from a test that owns
 * the only board, and [reset] it first.
 */
object DrawBoxRenderStats {
    /** Elements drawn, live or into the cached layer. */
    var elementsDrawn: Long = 0L
        internal set

    /** Times the cached layer of unchanging elements was recorded. */
    var layerRecords: Long = 0L
        internal set

    fun reset() {
        elementsDrawn = 0L
        layerRecords = 0L
    }
}
