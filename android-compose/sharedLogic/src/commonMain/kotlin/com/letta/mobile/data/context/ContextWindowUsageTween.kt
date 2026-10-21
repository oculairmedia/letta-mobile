package com.letta.mobile.data.context

import kotlin.math.roundToInt

/**
 * The reading [progress] of the way (`0f..1f`) from this one to [target],
 * so a meter whose numbers change moves to them instead of jumping. Totals, the window and every
 * section's tokens travel together; a section only one side has grows from (or shrinks to) zero,
 * and the sections keep [target]'s order. At 1 it is [target] itself.
 */
fun ContextWindowUsage.towards(target: ContextWindowUsage, progress: Float): ContextWindowUsage {
    val t = progress.coerceIn(0f, 1f)
    if (t >= 1f || this == target) return target
    val window = lerp(maxTokens, target.maxTokens, t)
    val fromTokens = segments.associate { it.slot() to it.tokens }
    val toSlots = target.segments.map { it.slot() }.toSet()
    val moving = target.segments.map { it.copy(tokens = lerp(fromTokens[it.slot()] ?: 0, it.tokens, t)) } +
        segments.filter { it.slot() !in toSlots }.map { it.copy(tokens = lerp(it.tokens, 0, t)) }
    val used = lerp(usedTokens, target.usedTokens, t)
    val free = lerp(freeTokens, target.freeTokens, t)
    return ContextWindowUsage(
        usedTokens = used,
        maxTokens = window,
        freeTokens = free,
        segments = moving.filter { it.tokens > 0 }.map { it.copy(fraction = shareOf(it.tokens, window)) },
        freeSegment = target.freeSegment.copy(tokens = free, fraction = shareOf(free, window)),
    )
}

private fun ContextWindowSegment.slot(): Pair<ContextWindowSegmentKind, String> = kind to label

private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()

private fun shareOf(tokens: Int, window: Int): Float = if (window <= 0) 0f else (tokens.toFloat() / window).coerceIn(0f, 1f)
