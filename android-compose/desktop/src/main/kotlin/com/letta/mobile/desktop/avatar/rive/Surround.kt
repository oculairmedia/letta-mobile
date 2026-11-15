package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.min

/**
 * One radial light in a [Surround]: a colour fading to transparent from a centre, as an ellipse
 * (`aspect` stretches it along its own x, `rotation` turns it). Positions and radius are
 * fractions of the layer so the same look survives a resize.
 */
data class GradNode(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val radius: Float = 0.5f,
    val aspect: Float = 1f,
    val rotation: Float = 0f,
    val argb: Int = 0x80FFFFFF.toInt(),
)

/** A base colour plus any number of radial lights stacked on it: the bench's art-directable ground. */
data class Surround(val base: Int, val nodes: List<GradNode> = emptyList())

fun DrawScope.drawSurround(spec: Surround) {
    drawRect(Color(spec.base))
    val m = min(size.width, size.height)
    for (n in spec.nodes) {
        val r = (n.radius * m).coerceAtLeast(1f)
        val c = Color(n.argb)
        withTransform({
            translate(n.x * size.width, n.y * size.height)
            rotate(n.rotation, Offset.Zero)
            scale(n.aspect, 1f, Offset.Zero)
        }) {
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), Offset.Zero, r), r, Offset.Zero)
        }
    }
}

/**
 * Draws a [Surround] behind `content`, and when `editing` lets the nodes be dragged on the
 * canvas (a ring marks each; the selected one is brighter). A tap that is not on a node calls
 * `onTap` - the bench opens the base-colour palette from it.
 */
@Composable
fun SurroundLayer(
    spec: Surround,
    editing: Boolean,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Surround) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(spec)
    val editingNow by rememberUpdatedState(editing)
    val selectedNow by rememberUpdatedState(selected)

    fun nearest(p: Offset): Int {
        val s = current
        var best = -1
        var bestD = 28f * 1.5f
        s.nodes.forEachIndexed { i, n ->
            val d = hypot(p.x - n.x * size.width, p.y - n.y * size.height)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .drawBehind { drawSurround(spec) }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val i = if (editingNow) nearest(p) else -1
                    if (i >= 0) onSelect(i) else onTap()
                }
            }
            .pointerInput(Unit) {
                var dragging = -1
                detectDragGestures(
                    onDragStart = { p -> dragging = if (editingNow) nearest(p) else -1; if (dragging >= 0) onSelect(dragging) },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                ) { change, drag ->
                    if (dragging < 0 || size.width == 0) return@detectDragGestures
                    change.consume()
                    val s = current
                    val n = s.nodes[dragging]
                    val moved = n.copy(
                        x = (n.x + drag.x / size.width).coerceIn(-0.5f, 1.5f),
                        y = (n.y + drag.y / size.height).coerceIn(-0.5f, 1.5f),
                    )
                    onChange(s.copy(nodes = s.nodes.toMutableList().also { it[dragging] = moved }))
                }
            }
            .drawWithContent {
                drawContent()
                if (!editingNow) return@drawWithContent
                spec.nodes.forEachIndexed { i, n ->
                    val c = Offset(n.x * size.width, n.y * size.height)
                    val hot = i == selectedNow
                    drawCircle(Color.Black.copy(alpha = 0.5f), 9f, c, style = Stroke(4f))
                    drawCircle(if (hot) Color.White else Color(0xFFBBBBBB), 9f, c, style = Stroke(2f))
                    drawCircle(Color(n.argb).copy(alpha = 1f), 4f, c)
                }
            },
    ) { content() }
}
