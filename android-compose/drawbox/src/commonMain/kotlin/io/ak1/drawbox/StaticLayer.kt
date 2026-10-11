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
    private var recordedOffset: Offset? = null
    private var recordedScale = 0f
    private var recordedCanvas = IntSize.Zero
    private var recordedExcluded: Set<String> = emptySet()
    private var recordedWorld = Rect.Zero
    private var margin = Offset.Zero
    private var recordedImages = -1L

    /**
     * Whether the recording still shows the board for [vp] at [canvas] with [excluded] drawn live,
     * [images] being the image cache's count of landed decodes (a placeholder recorded before its
     * image landed is stale).
     */
    fun isFresh(vp: Viewport, canvas: IntSize, excluded: Set<String>, changes: List<RenderIndex.Change>, images: Long): Boolean {
        val at = recordedOffset ?: return false
        if (images != recordedImages) return false
        if (vp.scale != recordedScale || canvas != recordedCanvas || excluded != recordedExcluded) return false
        if (abs(vp.offset.x - at.x) > margin.x || abs(vp.offset.y - at.y) > margin.y) return false
        return changes.none { it.id !in excluded && it.bounds.overlaps(recordedWorld) }
    }

    /** Records the elements [index] has in the region around [vp], except [excluded]. */
    fun DrawScope.record(
        vp: Viewport,
        index: RenderIndex,
        excluded: Set<String>,
        images: Long,
        draw: DrawScope.(Element) -> Unit,
    ) {
        val canvas = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val mx = ceil(size.width * MARGIN).toInt().toFloat()
        val my = ceil(size.height * MARGIN).toInt().toFloat()
        val world = Rect(vp.screenToWorld(Offset(-mx, -my)), vp.screenToWorld(Offset(size.width + mx, size.height + my)))
        val elements = index.query(world)
        layer.record(size = IntSize((canvas.width + 2 * mx).toInt(), (canvas.height + 2 * my).toInt())) {
            withTransform({
                translate(mx + vp.offset.x, my + vp.offset.y)
                scale(vp.scale, vp.scale, pivot = Offset.Zero)
            }) {
                elements.forEach { if (it.id !in excluded) draw(it) }
            }
        }
        recordedOffset = vp.offset
        recordedScale = vp.scale
        recordedCanvas = canvas
        recordedExcluded = excluded
        recordedWorld = world
        recordedImages = images
        margin = Offset(mx, my)
        DrawBoxRenderStats.layerRecords++
    }

    /** Replays the recording where [vp] puts it now. */
    fun DrawScope.replay(vp: Viewport) {
        val at = recordedOffset ?: return
        translate(vp.offset.x - at.x - margin.x, vp.offset.y - at.y - margin.y) { drawLayer(layer) }
    }

    private companion object {
        /** Extra recorded on each side, as a fraction of the canvas: a pan this far replays for free. */
        const val MARGIN = 0.5f
    }
}
