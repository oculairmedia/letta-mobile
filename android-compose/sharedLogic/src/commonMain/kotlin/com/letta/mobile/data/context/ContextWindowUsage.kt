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
 * counts, and the two can disagree (sections the overview does not itemise).
 * The reported total wins for [usedTokens] so the header matches the server,
 * and free space is derived from it rather than from the segment sum.
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
            val itemised = itemisedSegments(overview)
            val max = overview.contextWindowSizeMax.coerceAtLeast(0)
            val used = overview.contextWindowSizeCurrent
                .takeIf { it > 0 }
                ?: itemised.sumOf { (_, _, tokens) -> tokens }
            val boundedUsed = if (max > 0) used.coerceIn(0, max) else used.coerceAtLeast(0)
            val free = (max - boundedUsed).coerceAtLeast(0)
            val segments = itemised
                .filter { (_, _, tokens) -> tokens > 0 }
                .sortedByDescending { (_, _, tokens) -> tokens }
                .map { (kind, label, tokens) ->
                    ContextWindowSegment(kind, label, tokens, fractionOf(tokens, max))
                }
            return ContextWindowUsage(
                usedTokens = boundedUsed,
                maxTokens = max,
                freeTokens = free,
                segments = segments,
                freeSegment = ContextWindowSegment(
                    kind = ContextWindowSegmentKind.FreeSpace,
                    label = "Free space",
                    tokens = free,
                    fraction = fractionOf(free, max),
                ),
            )
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
