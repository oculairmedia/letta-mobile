package com.letta.mobile.ui.context

import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.data.context.limit.ContextLimitAdvice
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.context.limit.ContextLimitWarning

/** letta-mobile-joigh: the context-limit slider's text, beside [AgentContextStrings]. */
object ContextLimitStrings {
    const val LIMIT = "Limit"
    const val COMPACT = "Compact"
    const val UNSUPPORTED = "This host can't change the context limit: its letta-code has no /context-limit."

    /** "128k of 1M": the candidate limit against the model's window, or the limit alone when that is unknown. */
    fun valueLabel(tokens: Int, modelMax: Int?): String =
        modelMax?.let { "${formatContextTokens(tokens)} of ${formatContextTokens(it)}" } ?: formatContextTokens(tokens)

    /**
     * The true reach of a change, as letta-code's `/context-limit` applies it: on the default
     * conversation it updates the agent (other conversations without their own limit follow it);
     * on any other conversation it sets that conversation's own limit, which then outlasts model
     * switches there.
     */
    fun scope(scope: ContextLimitScope): String = when (scope) {
        ContextLimitScope.Agent -> "Sets the agent's limit; conversations with their own limit keep it"
        ContextLimitScope.Conversation -> "This conversation only; the agent's limit is unchanged"
    }

    /** The line under the slider: the warning for the stop under the thumb, else where it auto-compacts. */
    fun status(advice: ContextLimitAdvice, usedTokens: Int?, maxKnown: Boolean, failure: String?): String {
        failure?.let { return "Couldn't change the limit: $it" }
        val threshold = advice.autoCompactAtTokens?.let(::formatContextTokens)
        return when (advice.warning) {
            ContextLimitWarning.ExceedsModel -> "Above this model's window: letta-code refuses it and the provider would overflow"
            ContextLimitWarning.BelowUsage ->
                "Below the ${usedTokens?.let(::formatContextTokens).orEmpty()} already in context: compact first, or the next turn compacts to fit"
            ContextLimitWarning.CompactsNextTurn -> "Past the auto-compact point ($threshold): the next turn compacts"
            ContextLimitWarning.None -> buildString {
                append("Auto-compacts at ${threshold.orEmpty()}")
                if (!maxKnown) append(" · this model's maximum isn't listed; pick a limit it supports")
            }
        }
    }
}
