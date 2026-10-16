package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import com.letta.mobile.avatar.core.MascotBody
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * letta-mobile-te3n1.3: how a transported character moves between seats, read off its
 * [MascotBody]. The drawing composable reads none of this in composition: placement and the layer
 * read the flight's frame counter, so a hop or an entrance re-places and re-draws the character
 * without recomposing it, and nothing here allocates per frame.
 */

/** Lerp of two floats; [t] may run past 1 while the body overshoots. */
private fun mix(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/** The character's centre x this frame, window px. */
private fun MascotTransport.Flight.centerX(to: Rect): Float {
    val t = body.travel
    return if (t == 1f) to.center.x else mix(from.center.x, to.center.x, t)
}

/** The character's centre y this frame, window px, lifted along the hop's arc. */
private fun MascotTransport.Flight.centerY(to: Rect): Float {
    val t = body.travel
    return if (t == 1f) to.center.y else mix(from.center.y, to.center.y, t) - arcLift(to)
}

/** The character's width this frame: the seat's sizes blended along the hop, never past either. */
private fun MascotTransport.Flight.width(to: Rect): Float {
    val t = body.travel.coerceIn(0f, 1f)
    return if (t == 1f) to.width else mix(from.width, to.width, t)
}

/**
 * A long hop rises over its path (a parabola, highest half-way); a short one stays level. Never
 * more than half the destination seat, so a hop across the window is a hop, not a lob.
 */
private fun MascotTransport.Flight.arcLift(to: Rect): Float {
    val t = body.travel.coerceIn(0f, 1f)
    val dx = to.center.x - from.center.x
    val dy = to.center.y - from.center.y
    val distance = sqrt(dx * dx + dy * dy)
    if (distance < to.width * ARC_MIN_SEATS) return 0f
    return min(distance * ARC_FRACTION, to.height * ARC_MAX_SEATS) * 4f * t * (1f - t)
}

/** Where the character is drawn now, as a rect; a hop that starts mid-flight starts here. */
internal fun MascotTransport.Flight.shownRect(to: Rect): Rect {
    val x = centerX(to)
    val y = centerY(to)
    val half = width(to) / 2f
    return Rect(x - half, y - half, x + half, y + half)
}

/**
 * The box's offset in the layer for this frame, a [drawWidth] square centred on the character;
 * also remembers where it was placed so a hop whose seat has gone starts from there.
 */
internal fun MascotTransport.Flight.placement(seat: MascotSeatInfo, origin: Offset): IntOffset {
    val to = seat.bounds
    val drawWidth = seat.drawWidth
    // Read so a moving body re-places the box each frame (placement only, never composition).
    observeFrame()
    val x = centerX(to)
    val y = centerY(to)
    drawnX = x
    drawnY = y
    drawnWidth = width(to)
    hasDrawn = true
    return IntOffset((x - drawWidth / 2f - origin.x).roundToInt(), (y - drawWidth / 2f - origin.y).roundToInt())
}

/**
 * The character's shape this frame on its layer: the seat-to-seat size as a scale, the body's
 * squash, stretch and lean (unless [deform] is off because the rig draws them from the body's
 * motion), and the entrance, all about its feet. Under [reducedMotion] only the entrance's fade
 * remains: no squash, no lean, no rise.
 */
internal fun MascotTransport.Flight.applyPose(scope: GraphicsLayerScope, seat: MascotSeatInfo, reducedMotion: Boolean, deform: Boolean) {
    // Read so a moving body re-draws the layer each frame (draw only, never composition).
    observeFrame()
    val to = seat.bounds
    val drawWidth = seat.drawWidth
    val flightScale = if (drawWidth > 0f) width(to) / drawWidth else 1f
    val shown = presence.value
    val enter = if (reducedMotion) 1f else MascotBody.entranceScale(shown)
    val enterDeform = if (reducedMotion) 0f else MascotBody.entranceDeform(shown, body.tune)
    val bodyX = if (deform) body.scaleX else 1f
    val bodyY = if (deform) body.scaleY else 1f
    with(scope) {
        transformOrigin = FEET
        scaleX = flightScale * enter * bodyX * exp(-enterDeform)
        scaleY = flightScale * enter * bodyY * exp(enterDeform)
        rotationZ = if (deform) body.rotationDegrees else 0f
        alpha = if (reducedMotion) shown else (shown * ENTRANCE_ALPHA_RATE).coerceAtMost(1f)
        // Scaled about the feet, a smaller seat would drop the character's middle: lift it by the
        // unused height so the seat-to-seat scale stays centred, as it was before the body.
        translationY = -(1f - flightScale) * size.height / 2f
    }
}

/**
 * Steps [MascotTransport.Flight.body] every frame while it moves, from each wake until it rests
 * again; [onLanded] hears each landing's impact. Idle, it waits on the next wake and costs nothing.
 * Under [reducedMotion] each wake stands the body still instead.
 */
internal suspend fun MascotTransport.Flight.drive(reducedMotion: Boolean, onLanded: (Float) -> Unit) {
    snapshotFlow { wakes }.collect {
        if (body.atRest) return@collect
        if (reducedMotion) {
            // No springs under reduced motion: whatever set it moving (a drag, a poke) stands still.
            body.snap()
            frame++
            return@collect
        }
        var last = withFrameNanos { it }
        while (!body.atRest) {
            val now = withFrameNanos { it }
            // A long frame slows the motion down instead of jumping it ahead.
            val impact = body.step(((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS))
            last = now
            frame++
            if (impact > 0f) onLanded(impact)
        }
        frame++
    }
}

/**
 * The slot's first appearance: the character rises from its feet with one overshoot and lands
 * ([onLanded], plus a small squash). Under [reducedMotion] a short fade, nothing else. Nothing
 * when it is already standing.
 */
internal suspend fun MascotTransport.Flight.enter(reducedMotion: Boolean, onLanded: (Float) -> Unit) {
    if (presence.value >= 1f) return
    if (reducedMotion) {
        presence.animateTo(1f, tween(REDUCED_FADE_MILLIS, easing = LinearEasing))
        return
    }
    presence.animateTo(1f, tween(ENTRANCE_MILLIS, easing = LinearEasing))
    body.impulse(ENTRANCE_IMPACT)
    wakes++
    onLanded(ENTRANCE_IMPACT)
}

private val FEET = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_SECONDS = 1f / 20f

/** A hop rises when it covers more than this many destination-seat widths. */
private const val ARC_MIN_SEATS = 2f

/** How high a long hop rises, as a fraction of its length and at most of the seat's height. */
private const val ARC_FRACTION = 0.12f
private const val ARC_MAX_SEATS = 0.5f

/** The entrance's rise; the curve ([MascotBody.entranceScale]) shapes it, so it runs linear. */
private const val ENTRANCE_MILLIS = 420
private const val ENTRANCE_IMPACT = 0.45f

/** Fully opaque a third of the way in: the rise reads, not a fade. */
private const val ENTRANCE_ALPHA_RATE = 3f

/** Reduced motion: the entrance is this fade and nothing more. */
internal const val REDUCED_FADE_MILLIS = 120
