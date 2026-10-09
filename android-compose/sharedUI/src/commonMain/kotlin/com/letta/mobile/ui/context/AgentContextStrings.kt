package com.letta.mobile.ui.context

import com.letta.mobile.data.context.CompactionNotice
import com.letta.mobile.data.context.ContextProvenance
import com.letta.mobile.data.context.ModelChangeScope

/**
 * letta-mobile-3io8k: the drawer context card's and sheet's text, in Kotlin like
 * [com.letta.mobile.ui.modelcontrol.ModelControlStrings], so the move to `Res.string` is one file.
 */
object AgentContextStrings {
    const val MODEL_FALLBACK = "Model"
    const val CONTEXT_TITLE = "Context"
    const val MODEL_TITLE = "Model"
    const val COMPACT = "Compact conversation"
    const val COMPACTING = "Compacting…"
    const val NO_READING = "No context reading yet"
    const val TOTAL_ONLY_HINT = "Breakdown not available from this host."
    const val NEAR_FULL_HINT = "Nearly full. Compacting now keeps the next turn from being cut short."
    const val OPEN_SHEET = "Model and context"

    fun usedLine(percent: Int?, window: String?, used: String?): String = when {
        percent != null && window != null -> "$percent% of $window used"
        used != null -> "$used used"
        else -> NO_READING
    }

    fun totalLine(used: String, window: String?, percent: Int?): String =
        if (window != null && percent != null) "$used / $window ($percent%)" else used

    fun scope(scope: ModelChangeScope): String = when (scope) {
        ModelChangeScope.Agent -> "Applies to this agent"
        ModelChangeScope.Conversation -> "Applies to this conversation"
    }

    fun provenance(provenance: ContextProvenance, totalIsEstimate: Boolean): String = when {
        totalIsEstimate -> "Estimated after compaction"
        provenance == ContextProvenance.EstimatedCalibrated -> "Estimated, matched to provider total"
        provenance == ContextProvenance.Estimated -> "Estimated"
        else -> "Total only"
    }

    fun notice(notice: CompactionNotice, counts: Pair<Int, Int>?, detail: String?): String? = when (notice) {
        CompactionNotice.None -> null
        CompactionNotice.Compacted -> counts?.let { (before, after) -> "Compacted: $before → $after messages" } ?: "Compacted"
        CompactionNotice.AlreadyCompact -> "Already compact"
        CompactionNotice.Pending -> "Still compacting; the meter updates after the next turn"
        CompactionNotice.Failed -> detail?.let { "Compaction failed: $it" } ?: "Compaction failed"
    }
}
