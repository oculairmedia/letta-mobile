package com.letta.mobile.data.context

import com.letta.mobile.data.compaction.CompactionOutcome
import kotlin.math.roundToInt

/** letta-mobile-3io8k: how full the window is, for the bar's tone (neutral < 70 %, warning, error >= 90 %). */
enum class ContextMeterLevel {
    Normal,
    Warning,
    Critical,
    ;

    companion object {
        fun of(fraction: Float): ContextMeterLevel = when {
            fraction >= CRITICAL_AT -> Critical
            fraction >= WARNING_AT -> Warning
            else -> Normal
        }

        const val WARNING_AT: Float = 0.70f
        const val CRITICAL_AT: Float = 0.90f
    }
}

/** Where a model pick applies: letta-code's `update_model` targets the agent on its default conversation. */
enum class ModelChangeScope { Agent, Conversation }

/** The Compact button's state. */
enum class CompactAffordance {
    /** The backend cannot compact (old host, no route): no button. */
    Hidden,
    Available,

    /** Disabled while this conversation compacts or a turn runs. */
    Busy,
}

/** What the last compaction attempt came to, for one line under the button. */
enum class CompactionNotice { None, Compacted, AlreadyCompact, Pending, Failed }

/** Everything the drawer card and its sheet derive their copy from. */
data class AgentContextCardInputs(
    val modelLabel: String?,
    val effort: String?,
    val meter: ContextMeter?,
    val conversationIsDefault: Boolean,
    val compactSupported: Boolean?,
    val compacting: Boolean,
    val turnRunning: Boolean,
    val lastOutcome: CompactionOutcome? = null,
)

/**
 * letta-mobile-3io8k: the drawer card's presentation — the model line, the meter, and the
 * sheet's Compact state — derived from [AgentContextCardInputs] alone so both platforms say the
 * same thing.
 */
data class AgentContextCardModel(
    val modelLabel: String?,
    val effort: String?,
    val meter: ContextMeter?,
    /** Whole percent of the window in use; null when the window or the total is unknown. */
    val usedPercent: Int?,
    val level: ContextMeterLevel,
    /** Amber "Compact" nudge from 85 % (letta-code auto-compacts a little above that). */
    val nudgeCompact: Boolean,
    val scope: ModelChangeScope,
    val compact: CompactAffordance,
    val notice: CompactionNotice,
    /** The failure text behind [CompactionNotice.Failed]. */
    val noticeDetail: String? = null,
    /** Message counts behind [CompactionNotice.Compacted] ("48 → 12"). */
    val messageCounts: Pair<Int, Int>? = null,
) {
    companion object {
        const val NUDGE_AT: Float = 0.85f

        fun present(inputs: AgentContextCardInputs): AgentContextCardModel {
            val usage = inputs.meter?.usage
            val fraction = usage?.takeIf { it.maxTokens > 0 }?.let { it.usedTokens.toFloat() / it.maxTokens.toFloat() }
            return AgentContextCardModel(
                modelLabel = inputs.modelLabel,
                effort = inputs.effort,
                meter = inputs.meter,
                usedPercent = fraction?.let { (it * PERCENT).roundToInt() },
                level = fraction?.let(ContextMeterLevel::of) ?: ContextMeterLevel.Normal,
                nudgeCompact = fraction != null && fraction >= NUDGE_AT,
                scope = if (inputs.conversationIsDefault) ModelChangeScope.Agent else ModelChangeScope.Conversation,
                compact = affordance(inputs),
                notice = notice(inputs.lastOutcome),
                noticeDetail = (inputs.lastOutcome as? CompactionOutcome.Failed)?.message,
                messageCounts = (inputs.lastOutcome as? CompactionOutcome.Compacted)?.result?.let { result ->
                    val before = result.messagesBefore ?: return@let null
                    val after = result.messagesAfter ?: return@let null
                    before to after
                },
            )
        }

        /**
         * letta-mobile-3io8k: a model whose window is smaller than what the conversation already
         * holds overflows on its next turn; the picker warns before the switch.
         */
        fun overflowsWindow(usedTokens: Int?, candidateWindow: Int?): Boolean {
            val used = usedTokens ?: return false
            val window = candidateWindow?.takeIf { it > 0 } ?: return false
            return used > window
        }

        private fun affordance(inputs: AgentContextCardInputs): CompactAffordance = when {
            inputs.compactSupported == false -> CompactAffordance.Hidden
            inputs.compacting || inputs.turnRunning -> CompactAffordance.Busy
            else -> CompactAffordance.Available
        }

        private fun notice(outcome: CompactionOutcome?): CompactionNotice = when (outcome) {
            is CompactionOutcome.Compacted -> CompactionNotice.Compacted
            is CompactionOutcome.AlreadyCompact -> CompactionNotice.AlreadyCompact
            CompactionOutcome.Pending -> CompactionNotice.Pending
            is CompactionOutcome.Failed -> CompactionNotice.Failed
            CompactionOutcome.Unsupported, CompactionOutcome.Busy, null -> CompactionNotice.None
        }

        private const val PERCENT = 100f
    }
}
