package com.letta.mobile.ui.chat.surface.timeline

/**
 * letta-mobile-29sxj: the UI-side invariants of a send, echo, stream and settle, each read off the
 * [UiFrame]s the harness recorded. Every helper returns the offending frames, empty when the
 * sequence is clean, so a failing test prints exactly what jumped.
 */

/** Rows present in two consecutive frames whose height changed by more than [maxDp]. */
internal fun heightJumps(frames: List<UiFrame>, maxDp: Float): List<String> =
    frames.zipWithNext().flatMap { (before, after) ->
        val heights = before.rows.associate { it.key to it.height }
        after.rows.mapNotNull { row ->
            val was = heights[row.key] ?: return@mapNotNull null
            "frame ${after.index} [${after.step}]: ${row.key} went from $was to ${row.height}dp"
                .takeIf { kotlin.math.abs(row.height - was) > maxDp }
        }
    }

/**
 * Frames that show a spinner after the list has already drawn rows: once there is something to
 * read, a refresh must not put the loading state back over it.
 */
internal fun spinnerFlashes(frames: List<UiFrame>): List<UiFrame> {
    val firstRows = frames.indexOfFirst { it.rows.any { row -> !row.key.isFooterKey() } }
    if (firstRows < 0) return emptyList()
    return frames.drop(firstRows + 1).filter { it.spinnerVisible }
}

/**
 * Frames that were given a scroll command although the reader was following the tail and the
 * newest row had not changed since the frame before: the page chasing the tail on every emission
 * instead of only when a new newest row appears.
 */
internal fun scrollResetsWhileFollowing(frames: List<UiFrame>): List<UiFrame> =
    frames.zipWithNext().filter { (before, after) ->
        before.atNewestEdge && after.scrollCommands > 0 && before.newestKey == after.newestKey
    }.map { it.second }

/**
 * How many times a row's content was composed in each frame of [step] in which no new row
 * appeared: the frames where only a streamed token changed, so only the streaming row may compose.
 */
internal fun recompositionsPerFrame(frames: List<UiFrame>, step: String): List<Int> =
    frames.zipWithNext().filter { (before, after) -> after.step == step && before.newestKey == after.newestKey }
        .map { it.second.rowCompositions }

private fun String.isFooterKey(): Boolean = this == "canonical-loading" || this == "canonical-retry" || this == "__thinking__"
