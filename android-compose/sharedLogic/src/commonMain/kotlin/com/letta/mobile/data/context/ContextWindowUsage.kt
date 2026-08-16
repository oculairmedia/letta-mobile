package com.letta.mobile.data.context

import com.letta.mobile.data.model.ContextWindowOverview
import kotlin.math.roundToInt

/**
 * Which part of the prompt a [ContextWindowSegment] accounts for. The kind is
 * what UIs key their colours off, so it stays stable even when the label copy
 * changes.
 */
enum class ContextWindowSegmentKind {
    System,
    ToolDefinitions,
    ToolRules,
    CoreMemory,
    MemoryFiles,
    Directories,
    SummaryMemory,
    ExternalMemorySummary,
    Messages,

    /** Counted in the server's used total but not broken out by section. */
    Unitemised,
    FreeSpace,
}

/** One row of the context window breakdown. */
data class ContextWindowSegment(
    val kind: ContextWindowSegmentKind,
    val label: String,
    val tokens: Int,
    /** Share of the whole window, `0f..1f`. Zero when the window size is unknown. */
    val fraction: Float,
)

/**
 * Platform-neutral projection of [ContextWindowOverview] into the breakdown a
 * client renders: occupied segments (largest first), then free space.
 *
 * The server reports the used total separately from the per-section token
 * counts, and the two can disagree — so the projection reconciles them rather
 * than letting a bar drawn from segments contradict a header drawn from the
 * total:
 *
 *  - reported total above the itemised sum: the difference becomes an explicit
 *    [ContextWindowSegmentKind.Unitemised] segment, since the server is telling
 *    us the prompt holds sections its overview does not break out;
 *  - itemised sum above the reported total: the parts win, because a total that
 *    contradicts its own components cannot be shown alongside them;
 *  - parts overflowing the stated window: [maxTokens] widens to hold them — a
 *    window smaller than its own contents is not a window to draw against.
 *
 * The result is exact: every segment's [ContextWindowSegment.fraction] plus
 * [freeSegment]'s totals `1f`, and [usedTokens] equals the sum of the segments.
 */
data class ContextWindowUsage(
    val usedTokens: Int,
    val maxTokens: Int,
    val freeTokens: Int,
    /** Occupied segments only, largest first; free space is [freeSegment]. */
    val segments: List<ContextWindowSegment>,
    val freeSegment: ContextWindowSegment,
) {
    /** Share of the window in use, `0f..1f`. Zero when the window size is unknown. */
    val usedFraction: Float get() = fractionOf(usedTokens, maxTokens)

    companion object {
        fun from(overview: ContextWindowOverview): ContextWindowUsage {
            val itemised = itemisedSegments(overview).filter { (_, _, tokens) -> tokens > 0 }
            val itemisedSum = itemised.sumOf { (_, _, tokens) -> tokens }
            val reported = overview.contextWindowSizeCurrent.coerceAtLeast(0)
            val used = maxOf(reported, itemisedSum)
            // A stated window of 0 means the server did not report one at all,
            // which is not the same as a window that is merely too small: with
            // no scale to draw against, every fraction stays 0 and there is no
            // free space to claim.
            val stated = overview.contextWindowSizeMax.coerceAtLeast(0)
            val window = if (stated > 0) maxOf(stated, used) else 0
            val free = (window - used).coerceAtLeast(0)
            val occupied = itemised + unitemisedRemainder(used - itemisedSum)
            val segments = occupied
                .sortedByDescending { (_, _, tokens) -> tokens }
                .map { (kind, label, tokens) ->
                    ContextWindowSegment(kind, label, tokens, fractionOf(tokens, window))
                }
            return ContextWindowUsage(
                usedTokens = used,
                maxTokens = window,
                freeTokens = free,
                segments = segments,
                freeSegment = ContextWindowSegment(
                    kind = ContextWindowSegmentKind.FreeSpace,
                    label = "Free space",
                    tokens = free,
                    fraction = fractionOf(free, window),
                ),
            )
        }

        /** The slice the server counts as used but never itemises. */
        private fun unitemisedRemainder(tokens: Int): List<Itemised> =
            if (tokens > 0) {
                listOf(Itemised(ContextWindowSegmentKind.Unitemised, "Other", tokens))
            } else {
                emptyList()
            }

        private data class Itemised(
            val kind: ContextWindowSegmentKind,
            val label: String,
            val tokens: Int,
        )

        private fun itemisedSegments(overview: ContextWindowOverview): List<Itemised> = listOf(
            Itemised(ContextWindowSegmentKind.Messages, "Messages", overview.numTokensMessages),
            Itemised(
                ContextWindowSegmentKind.ToolDefinitions,
                "Tool definitions",
                overview.numTokensFunctionsDefinitions,
            ),
            Itemised(ContextWindowSegmentKind.System, "System prompt", overview.numTokensSystem),
            Itemised(ContextWindowSegmentKind.CoreMemory, "Core memory", overview.numTokensCoreMemory),
            Itemised(ContextWindowSegmentKind.MemoryFiles, "Memory files", overview.numTokensMemoryFilesystem),
            Itemised(ContextWindowSegmentKind.Directories, "Directories", overview.numTokensDirectories),
            Itemised(ContextWindowSegmentKind.ToolRules, "Tool rules", overview.numTokensToolUsageRules),
            Itemised(ContextWindowSegmentKind.SummaryMemory, "Summary memory", overview.numTokensSummaryMemory),
            Itemised(
                ContextWindowSegmentKind.ExternalMemorySummary,
                "External memory",
                overview.numTokensExternalMemorySummary,
            ),
        )

        private fun fractionOf(tokens: Int, max: Int): Float =
            if (max <= 0) 0f else (tokens.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Compact token count: `842`, `50.2k`, `1M`. Trailing `.0` is dropped so round
 * numbers read as `1M` rather than `1.0M`.
 */
fun formatContextTokens(tokens: Int): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> "${trimZero(tokens / 1_000.0)}k"
    else -> "${trimZero(tokens / 1_000_000.0)}M"
}

/** Per-row share, one decimal place: `1.6%`, `95.0%`. */
fun formatContextShare(fraction: Float): String {
    val tenths = (fraction * 1_000f).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}

/** Header share, rounded to whole percent: `5%`. */
fun formatContextPercent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

private fun trimZero(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}
