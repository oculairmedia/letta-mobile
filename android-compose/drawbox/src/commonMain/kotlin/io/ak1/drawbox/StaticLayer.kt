package io.ak1.drawbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The board's unchanging elements, recorded once into a [GraphicsLayer] for a region a margin
 * larger than the screen, and replayed every frame.
 *
 * - Only elements painting into that region are recorded ([RenderIndex.query]): an element far
 *   off screen costs nothing per frame.
 * - A pan within the margin replays the same recording, shifted: no element is drawn again.
 * - A change re-records only when it paints into the region (as it was, or as it is now), or when
 *   the set of elements drawn live instead (being dragged, hidden under an editor) changes.
 * - A zoom, a resize, or a pan past the margin records the region anew around the new view.
 *
 * Not state: written from the draw pass, read by it; nothing composes from it.
 */
internal class StaticLayer(private val layer: GraphicsLayer) {
    /**
     * What a recording depends on besides where the view is: the zoom, the canvas size, the
     * elements drawn live instead, and the image cache's count of landed decodes (a placeholder
     * recorded before its image landed is stale).
     */
    data class Key(val scale: Float, val canvas: IntSize, val excluded: Set<String>, val images: Long)

    /** One recording: its [key], the view offset it was made at, the world it covers, its margin. */
    private class Recording(val key: Key, val offset: Offset, val world: Rect, val margin: Offset) {
        fun covers(vp: Viewport): Boolean =
            abs(vp.offset.x - offset.x) <= margin.x && abs(vp.offset.y - offset.y) <= margin.y

        fun touchedBy(change: RenderIndex.Change): Boolean =
            change.id !in key.excluded && change.bounds.overlaps(world)
    }

    private var recording: Recording? = null

    /** Whether the recording still shows the board for [vp] under [key], given [changes] since. */
    fun isFresh(vp: Viewport, key: Key, changes: List<RenderIndex.Change>): Boolean {
        val r = recording ?: return false
        return r.key == key && r.covers(vp) && changes.none(r::touchedBy)
    }

    /** Records the elements [index] has in the region around [vp], except those [key] excludes. */
    fun DrawScope.record(vp: Viewport, key: Key, index: RenderIndex, draw: DrawScope.(Element) -> Unit) {
        val margin = Offset(ceil(size.width * MARGIN), ceil(size.height * MARGIN))
        val world = Rect(vp.screenToWorld(-margin), vp.screenToWorld(Offset(size.width, size.height) + margin))
        val elements = index.query(world)
        val layerSize = IntSize((key.canvas.width + 2 * margin.x).toInt(), (key.canvas.height + 2 * margin.y).toInt())
        layer.record(size = layerSize) {
            withTransform({
                translate(margin.x + vp.offset.x, margin.y + vp.offset.y)
                scale(vp.scale, vp.scale, pivot = Offset.Zero)
            }) {
                elements.forEach { if (it.id !in key.excluded) draw(it) }
            }
        }
        recording = Recording(key, vp.offset, world, margin)
        DrawBoxRenderStats.layerRecords++
    }

    /** Replays the recording where [vp] puts it now. */
    fun DrawScope.replay(vp: Viewport) {
        val r = recording ?: return
        translate(vp.offset.x - r.offset.x - r.margin.x, vp.offset.y - r.offset.y - r.margin.y) { drawLayer(layer) }
    }

    private companion object {
        /** Extra recorded on each side, as a fraction of the canvas: a pan this far replays for free. */
        const val MARGIN = 0.5f
    }
}
