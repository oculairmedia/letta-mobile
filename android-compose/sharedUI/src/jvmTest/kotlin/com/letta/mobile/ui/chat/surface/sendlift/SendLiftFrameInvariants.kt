package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * letta-mobile-86njl.1: the send choreography's invariants as pure functions over the recorded
 * frames. Each returns the violations, one readable line each; empty means the invariant holds.
 */
private const val SIZE_TOLERANCE_PX = 0.5f
private const val POSITION_TOLERANCE_PX = 1f
private const val ONE_FRAME_MILLIS = SendLiftFrameRecorder.FRAME_MILLIS
private const val COMPANION_OPEN_FRAMES = 2
private const val MAX_ARRIVAL_COMPOSITIONS = 2

private fun List<SendFrame>.pairs(): List<Pair<SendFrame, SendFrame>> = zipWithNext()

/** Frames from the send frame (t = 0) on. */
internal fun List<SendFrame>.fromSend(): List<SendFrame> = filter { it.t >= 0 }

/** Frames up to and including [millis] after the send frame. */
internal fun List<SendFrame>.until(millis: Int): List<SendFrame> = filter { it.t <= millis }

private fun Rect.sizeDiffers(other: Rect): Boolean =
    abs(width - other.width) > SIZE_TOLERANCE_PX || abs(height - other.height) > SIZE_TOLERANCE_PX

private fun Rect.boundsDiffer(other: Rect): Boolean =
    sizeDiffers(other) || abs(left - other.left) > POSITION_TOLERANCE_PX || abs(top - other.top) > POSITION_TOLERANCE_PX

/** Any frame where the prompt the person sees (the ghost, then the row's bubble) changes size from the one before. */
internal fun bubbleSizeJumps(frames: List<SendFrame>): List<String> = frames.pairs().mapNotNull { (before, after) ->
    val was = before.visiblePrompt
    val now = after.visiblePrompt
    if (was != null && now != null && was.sizeDiffers(now)) "t=${after.t}: prompt ${was.width}x${was.height} -> ${now.width}x${now.height}" else null
}

/** The previous row's top must only move up, and never by more than [maxPxPerFrame] in a frame. */
internal fun olderRowStepJumps(frames: List<SendFrame>, maxPxPerFrame: Float): List<String> = frames.pairs().mapNotNull { (before, after) ->
    val was = before.olderRow?.top
    val now = after.olderRow?.top
    val step = if (was != null && now != null) now - was else 0f
    if (step > POSITION_TOLERANCE_PX || step < -maxPxPerFrame) "t=${after.t}: previous row top $was -> $now (${step}px)" else null
}

/**
 * The real bubble never changes bounds after its first frame; and where a ghost flew before it, the
 * ghost's last frame matches the row at the hand-off within a pixel.
 */
internal fun bubbleBoundsConstantFromFirstFrame(frames: List<SendFrame>): List<String> =
    boundsChangesAfterFirstFrame(frames) + ghostMismatchAtHandOff(frames)

private fun boundsChangesAfterFirstFrame(frames: List<SendFrame>): List<String> {
    val first = frames.firstNotNullOfOrNull { it.promptBubble } ?: return listOf("the prompt row never drew")
    return frames.filter { it.promptBubble?.boundsDiffer(first) == true }
        .map { "t=${it.t}: bubble ${it.promptBubble} differs from its first frame $first" }
}

private fun ghostMismatchAtHandOff(frames: List<SendFrame>): List<String> = frames.pairs().mapNotNull { (before, after) ->
    val ghost = before.ghost
    val row = after.promptBubble
    if (ghost != null && after.ghost == null && row != null && ghost.boundsDiffer(row)) "hand-off at t=${after.t}: ghost $ghost vs row $row" else null
}

/** From the send frame on, timeline rows compose at most the sent prompt's first composition and its chevron frame. */
internal fun rowRecompositionsDuringArrival(frames: List<SendFrame>): List<String> {
    val total = frames.fromSend().sumOf { it.rowCompositions }
    return if (total > MAX_ARRIVAL_COMPOSITIONS) listOf("$total row compositions from the send frame, at most $MAX_ARRIVAL_COMPOSITIONS allowed") else emptyList()
}

/** The list ends on its newest edge. */
internal fun listEndsAtNewestEdge(frames: List<SendFrame>): List<String> {
    val last = frames.last()
    return if (last.atNewestEdge) emptyList() else listOf("ends at item ${last.firstVisibleItemIndex} offset ${last.scrollOffset}, not the newest edge")
}

/** Within [millis] of the send frame the whole bubble is in the part of the list the person sees. */
internal fun promptFullyVisibleWithin(frames: List<SendFrame>, millis: Int): List<String> {
    val shown = frames.until(millis).fromSend().any { frame ->
        val bubble = frame.promptBubble
        bubble != null && bubble.top >= frame.viewport.top && bubble.bottom <= frame.viewport.bottom + POSITION_TOLERANCE_PX
    }
    return if (shown) emptyList() else listOf("the prompt was not fully inside the viewport within ${millis}ms")
}

/** Nothing (ghost or departing text) is still animating more than [millis] and a frame after the send frame. */
internal fun noFlightLongerThan(frames: List<SendFrame>, millis: Int): List<String> =
    frames.filter { it.t > millis + ONE_FRAME_MILLIS && (it.ghostCount > 0 || it.departingTextCount > 0) }
        .map { "t=${it.t}: still in flight (ghost=${it.ghostCount}, departing=${it.departingTextCount})" }

/** The departing draft text is gone [millis] and a frame after the send frame. */
internal fun departingTextGoneWithin(frames: List<SendFrame>, millis: Int): List<String> =
    frames.filter { it.t > millis + ONE_FRAME_MILLIS && it.departingTextCount > 0 }
        .map { "t=${it.t}: the departing text is still up" }

/** The companion row has started to open within two frames of the send frame. */
internal fun companionRowOpensWithTheSend(frames: List<SendFrame>): List<String> {
    val opened = frames.until(ONE_FRAME_MILLIS * COMPANION_OPEN_FRAMES).fromSend().any { it.companionRowHeight > SIZE_TOLERANCE_PX }
    val firstOpen = frames.firstOrNull { it.t >= 0 && it.companionRowHeight > SIZE_TOLERANCE_PX }?.t
    return if (opened) emptyList() else listOf("the companion row first opens at t=$firstOpen")
}

/** Once the chevron is up it stays up. */
internal fun chevronNeverBlinks(frames: List<SendFrame>): List<String> {
    val firstUp = frames.indexOfFirst { it.chevronCount > 0 }
    if (firstUp < 0) return emptyList()
    return frames.drop(firstUp).filter { it.chevronCount == 0 }.map { "t=${it.t}: the chevron is gone for a frame" }
}
