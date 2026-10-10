package com.letta.mobile.data.context

import com.letta.mobile.data.context.estimate.ContextBreakdownEstimator
import com.letta.mobile.data.context.estimate.ContextSections
import com.letta.mobile.data.context.estimate.ESTIMATE_SOURCE
import com.letta.mobile.data.model.ContextWindowOverview

/**
 * letta-mobile-cyh28: how far a context reading can be trusted.
 *
 * Only the total is ever exact (the provider's `context_tokens`); every per-section figure is an
 * estimate, because letta-code has no tokenizer and no server keeps sections.
 */
enum class ContextProvenance {
    /** The streamed total and nothing else: one "In context" bar. */
    TotalOnly,

    /** Sections estimated from disk, not matched to an exact total (partial, or stale after a compaction). */
    Estimated,

    /** Sections estimated from disk and matched to the exact total, so the rows sum to it. */
    EstimatedCalibrated,
}

/** A context meter's whole state: the bar, how it was derived, and where auto-compaction fires. */
data class ContextMeter(
    val usage: ContextWindowUsage,
    val provenance: ContextProvenance,
    /** Share of the window at which letta-code auto-compacts, `0f..1f`; null when the window is unknown. */
    val autoCompactAt: Float?,
    /** True when the total itself is a post-compaction estimate rather than a provider figure. */
    val totalIsEstimate: Boolean = false,
) {
    companion object {
        /**
         * The meter for one conversation.
         *
         * [streamedTotal] (the latest `usage_statistics.context_tokens`) is the authority for the
         * used figure. A [breakdown] that was matched to it supplies the sections; an uncalibrated
         * one is still shown, labelled [ContextProvenance.Estimated]. A breakdown from a host that
         * predates the estimator (no `source`) is ignored: its numbers were placeholders. The
         * host's window (the conversation/agent record) wins over [windowTokens] (the model
         * catalog) because it carries per-conversation limits; [pinnedWindow] (a limit this
         * session just applied, letta-mobile-joigh) wins over both until the record is re-read.
         */
        fun of(
            streamedTotal: Int?,
            windowTokens: Int?,
            breakdown: ContextWindowOverview? = null,
            totalIsEstimate: Boolean = false,
            pinnedWindow: Int? = null,
        ): ContextMeter? {
            val estimate = breakdown?.takeIf { it.source == ESTIMATE_SOURCE }
            val window = windowOf(pinnedWindow, estimate, windowTokens)
            if (estimate == null) {
                val total = streamedTotal ?: return null
                return ContextMeter(ContextWindowUsage.total(total, window), ContextProvenance.TotalOnly, autoCompactAt(window), totalIsEstimate)
            }
            return estimated(estimate, exactTotal = streamedTotal?.takeUnless { totalIsEstimate }, window, totalIsEstimate)
        }

        private fun estimated(estimate: ContextWindowOverview, exactTotal: Int?, window: Int?, totalIsEstimate: Boolean): ContextMeter {
            val matched = exactTotal?.let(estimate::calibratedTo) ?: estimate
            val calibrated = matched.calibrated == true && !totalIsEstimate
            return ContextMeter(
                usage = ContextWindowUsage.from(matched.copy(contextWindowSizeMax = window ?: 0)).withEstimateLabels(),
                provenance = if (calibrated) ContextProvenance.EstimatedCalibrated else ContextProvenance.Estimated,
                autoCompactAt = autoCompactAt(window),
                totalIsEstimate = totalIsEstimate,
            )
        }

        /**
         * letta-mobile-joigh: the window in force — [pinnedWindow], else the host record in
         * [breakdown] (estimator answers only), else [windowTokens]. Null when none is known.
         */
        fun windowOf(pinnedWindow: Int?, breakdown: ContextWindowOverview?, windowTokens: Int?): Int? =
            pinnedWindow?.takeIf { it > 0 }
                ?: breakdown?.takeIf { it.source == ESTIMATE_SOURCE }?.contextWindowSizeMax?.takeIf { it > 0 }
                ?: windowTokens?.takeIf { it > 0 }

        /**
         * letta-code's `contextCompactionThreshold`: the window less `min(16384, max(1, 20%))`,
         * as a share of the window.
         */
        fun autoCompactAt(windowTokens: Int?): Float? {
            val window = windowTokens?.takeIf { it > 0 } ?: return null
            val reserve = minOf(AUTO_COMPACT_RESERVE_TOKENS, maxOf(1, (window * AUTO_COMPACT_RESERVE_RATIO).toInt()))
            return (window - reserve).coerceAtLeast(0).toFloat() / window.toFloat()
        }

        private const val AUTO_COMPACT_RESERVE_TOKENS = 16_384
        private const val AUTO_COMPACT_RESERVE_RATIO = 0.2
    }
}

/**
 * Re-matches the host's disk sections to [total], the newest exact figure, so the bar always sums
 * to the number the meter prints even when the streamed total moved after the breakdown loaded.
 */
private fun ContextWindowOverview.calibratedTo(total: Int): ContextWindowOverview {
    val sections = ContextSections(numTokensSystem, numTokensCoreMemory, numTokensSummaryMemory, numTokensMessages)
    val matched = ContextBreakdownEstimator.calibrate(sections, total)
    return copy(
        contextWindowSizeCurrent = matched.total,
        numTokensSystem = matched.sections.system,
        numTokensCoreMemory = matched.sections.memory,
        numTokensSummaryMemory = matched.sections.summary,
        numTokensMessages = matched.sections.messages,
        numTokensFunctionsDefinitions = matched.tools ?: 0,
        calibrated = true,
        toolsDerived = true,
    )
}

/**
 * The section names the estimator's rows mean: "Tools & other" is a residual (tool schemas plus
 * reasoning and cache rounding), "Memory blocks" the rendered core memory, "Summary" the latest
 * compaction's summary.
 */
private fun ContextWindowUsage.withEstimateLabels(): ContextWindowUsage =
    copy(segments = segments.map { segment -> ESTIMATE_LABELS[segment.kind]?.let { segment.copy(label = it) } ?: segment })

private val ESTIMATE_LABELS = mapOf(
    ContextWindowSegmentKind.System to "System prompt",
    ContextWindowSegmentKind.CoreMemory to "Memory blocks",
    ContextWindowSegmentKind.SummaryMemory to "Summary",
    ContextWindowSegmentKind.Messages to "Messages",
    ContextWindowSegmentKind.ToolDefinitions to "Tools & other",
)
