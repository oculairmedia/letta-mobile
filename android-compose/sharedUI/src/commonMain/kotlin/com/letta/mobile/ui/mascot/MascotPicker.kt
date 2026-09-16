package com.letta.mobile.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import kotlin.math.cos
import kotlin.math.sin

/** The rotation slider's resolution: 24 positions, 0 to 345 degrees. */
const val MASCOT_ROTATION_STEP: Int = 15

/**
 * The Grokbot-style identity picker: a grid of the eight bodies, drawn in the chosen colour and at
 * the chosen turn, the palette's colour dots, and the turn itself. Each body is the real mascot
 * paused, not a drawing of it, so what the user picks is what the agent becomes; hosts with no
 * renderer fall back to the flat silhouette. Shared by every platform's edit-agent surface.
 */
@Composable
fun MascotPicker(
    identity: MascotIdentity,
    onChange: (MascotIdentity) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    // A turn being dragged shows in the picker at once but reaches [onChange] only on release: the
    // caller persists every change, and a drag must be one write, not one per slider step.
    var draggedRotation by remember(identity) { mutableStateOf<Int?>(null) }
    val shown = draggedRotation?.let { identity.copy(rotationDegrees = it) } ?: identity
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Shape", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MascotChoiceRows(MascotShape.entries.toList(), columns = 4) { shape ->
            MascotShapeChoice(shape, shown, accent) { onChange(shown.copy(shape = shape)) }
        }
        Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MascotChoiceRows(MascotPalette.ALL, columns = 5) { argb ->
            MascotColorChoice(argb, shown, accent) { onChange(shown.copy(argb = argb)) }
        }
        MascotRotationChoice(
            rotationDegrees = shown.rotationDegrees,
            onDrag = { draggedRotation = it },
            // Reads the drag state when called, not the composition's `shown`: a tap on the track
            // can report the value and the release in one frame, before any recomposition.
            onRelease = {
                val released = draggedRotation
                draggedRotation = null
                if (released != null && released != identity.rotationDegrees) onChange(identity.copy(rotationDegrees = released))
            },
        )
    }
}

@Composable
private fun MascotRotationChoice(rotationDegrees: Int, onDrag: (Int) -> Unit, onRelease: () -> Unit) {
    Text(
        "Rotation $rotationDegrees°",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val positions = MascotIdentity.FULL_TURN / MASCOT_ROTATION_STEP
    Slider(
        value = snapRotation(rotationDegrees).toFloat(),
        onValueChange = { degrees -> onDrag(snapRotation(degrees.toInt())) },
        onValueChangeFinished = onRelease,
        valueRange = 0f..(MascotIdentity.FULL_TURN - MASCOT_ROTATION_STEP).toFloat(),
        steps = positions - 2,
        modifier = Modifier.semantics { contentDescription = "rotation" },
    )
}

/** [degrees] to the nearest slider position, folded into one turn. */
fun snapRotation(degrees: Int): Int {
    val step = MASCOT_ROTATION_STEP
    return MascotIdentity.normalizeRotation(((degrees + step / 2).floorDiv(step)) * step)
}

@Composable
private fun <T> MascotChoiceRows(
    items: List<T>,
    columns: Int,
    content: @Composable (T) -> Unit,
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { content(it) }
            }
        }
    }
}

@Composable
private fun MascotShapeChoice(
    shape: MascotShape,
    identity: MascotIdentity,
    accent: Color,
    onSelect: () -> Unit,
) {
    val selected = shape == identity.shape
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.9f else 0.4f))
            .border(if (selected) 2.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .semantics { contentDescription = shape.name.lowercase() },
        contentAlignment = Alignment.Center,
    ) {
        // letta-mobile-0bvjw: the option is the character itself, paused - the same Rive scene the
        // agent will draw, so the shape, the pose and the turn are what the user is actually
        // picking. The silhouette stays only for hosts with no renderer, where it is the honest
        // fallback rather than a second source of truth; there the turn is applied by Compose,
        // since only Rive rotates the real body.
        val candidate = identity.copy(shape = shape)
        val key = mascotPickerKey(shape)
        if (mascotCandidateAvailable(key, candidate)) {
            MascotCandidate(key, candidate, size = 36.dp * MASCOT_TILE_OVERSCALE)
        } else {
            MascotShapeGlyph(shape, identity.argb, 36.dp, Modifier.rotate(identity.rotationDegrees.toFloat()))
        }
    }
}

/**
 * The scene key for one picker option. Entries are keyed by this string, and an entry re-skins
 * itself when it is asked for a different identity - so two options sharing a key would fight over
 * one scene and every option would end up drawing the last shape asked for. The prefix keeps them
 * clear of the real agent ids that share the same table.
 */
internal fun mascotPickerKey(shape: MascotShape): String = "mascot-picker:${shape.name}"

@Composable
private fun MascotColorChoice(
    argb: Int,
    identity: MascotIdentity,
    accent: Color,
    onSelect: () -> Unit,
) {
    val selected = argb == identity.argb
    val hex = argb.toUInt().toString(16).padStart(8, '0')
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(if (selected) 3.dp else 1.dp, if (selected) accent else Color.Black.copy(alpha = 0.25f), CircleShape)
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .semantics { contentDescription = "colour $hex" },
    )
}

/** A mascot body as a flat silhouette in its colour - the identity at a glance, no renderer needed. */
@Composable
fun MascotShapeGlyph(shape: MascotShape, argb: Int, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).padding(2.dp)) { drawMascotShape(shape, Color(argb)) }
}

/** The eight silhouettes, normalised to the draw area. Approximations of `art/body-*.svg`. */
fun DrawScope.drawMascotShape(shape: MascotShape, color: Color) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2f, h / 2f)
    val r = minOf(w, h) / 2f
    when (shape) {
        MascotShape.CIRCLE -> drawCircle(color, r, c)
        MascotShape.ROUNDED_SQUARE -> drawRoundRect(color, Offset(c.x - r * 0.92f, c.y - r * 0.92f), Size(1.84f * r, 1.84f * r), androidx.compose.ui.geometry.CornerRadius(r * 0.16f))
        MascotShape.PILL -> drawRoundRect(color, Offset(c.x - r, c.y - r * 0.68f), Size(2 * r, 2 * r * 0.68f), androidx.compose.ui.geometry.CornerRadius(r * 0.68f))
        MascotShape.BLOB -> drawPath(blob(c, r), color)
        // Centred on the face like art/body-triangle.svg, so a turned glyph turns about the same point.
        MascotShape.TRIANGLE -> drawSoftPolygon(color, SoftPolygon(c, r * 1.0f, r * 0.16f).also { it.sides = 3; it.startDeg = -90f })
        MascotShape.HEXAGON -> drawSoftPolygon(color, SoftPolygon(c, r * 1.0f, r * 0.12f).also { it.sides = 6 })
        MascotShape.CLOUD -> drawCloud(color, c, r)
        MascotShape.DROP -> drawPath(drop(c, r), color)
    }
}

private fun DrawScope.drawCloud(color: Color, c: Offset, r: Float) {
    drawCircle(color, r * 0.46f, Offset(c.x - r * 0.42f, c.y + r * 0.18f))
    drawCircle(color, r * 0.56f, Offset(c.x + r * 0.05f, c.y - r * 0.16f))
    drawCircle(color, r * 0.44f, Offset(c.x + r * 0.5f, c.y + r * 0.2f))
    drawRoundRect(color, Offset(c.x - r * 0.62f, c.y + r * 0.05f), Size(r * 1.5f, r * 0.58f), androidx.compose.ui.geometry.CornerRadius(r * 0.29f))
}

private fun blob(c: Offset, r: Float): Path = Path().apply {
    // an uneven soft body: four cubic edges with unequal bulges
    val k = 0.55f
    moveTo(c.x, c.y - r * 0.98f)
    cubicTo(c.x + r * k * 1.2f, c.y - r * 0.98f, c.x + r * 1.02f, c.y - r * k * 0.8f, c.x + r * 1.02f, c.y + r * 0.05f)
    cubicTo(c.x + r * 1.02f, c.y + r * k * 1.3f, c.x + r * k * 0.9f, c.y + r * 0.96f, c.x - r * 0.05f, c.y + r * 0.96f)
    cubicTo(c.x - r * k * 1.3f, c.y + r * 0.96f, c.x - r * 0.98f, c.y + r * k * 0.9f, c.x - r * 0.98f, c.y - r * 0.1f)
    cubicTo(c.x - r * 0.98f, c.y - r * k * 1.1f, c.x - r * k * 0.8f, c.y - r * 0.98f, c.x, c.y - r * 0.98f)
    close()
}

private fun drop(c: Offset, r: Float): Path = Path().apply {
    val top = Offset(c.x, c.y - r * 1.02f)
    val cy = c.y + r * 0.22f
    val rr = r * 0.78f
    moveTo(top.x, top.y)
    cubicTo(c.x + rr * 0.55f, c.y - r * 0.5f, c.x + rr, cy - rr * 0.5f, c.x + rr, cy)
    cubicTo(c.x + rr, cy + rr * 0.55f, c.x + rr * 0.55f, cy + rr, c.x, cy + rr)
    cubicTo(c.x - rr * 0.55f, cy + rr, c.x - rr, cy + rr * 0.55f, c.x - rr, cy)
    cubicTo(c.x - rr, cy - rr * 0.5f, c.x - rr * 0.55f, c.y - r * 0.5f, top.x, top.y)
    close()
}

private class SoftPolygon(
    val center: Offset,
    val radius: Float,
    val corner: Float,
) {
    var sides: Int = 3
    var startDeg: Float = 0f
}

/** A regular polygon with rounded corners: filled, plus a round-joined stroke that softens the corners. */
private fun DrawScope.drawSoftPolygon(color: Color, spec: SoftPolygon) {
    val inner = spec.radius - spec.corner / 2f
    val path = Path()
    for (i in 0 until spec.sides) {
        val a = Math.toRadians((spec.startDeg + 360f * i / spec.sides).toDouble())
        val p = Offset(spec.center.x + inner * cos(a).toFloat(), spec.center.y + inner * sin(a).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = spec.corner, join = StrokeJoin.Round))
}
