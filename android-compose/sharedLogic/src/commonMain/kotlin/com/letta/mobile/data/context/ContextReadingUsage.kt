package com.letta.mobile.data.context

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/** What a chat surface knows about its focused conversation's context at one instant. */
data class ContextReadingInputs(
    val key: ContextWindowUsageKey,
    /** The conversation's latest `context_tokens`, or null while it has none. */
    val contextTokens: Int?,
    /** The model's window, or null when it is unknown. */
    val windowTokens: Int?,
)

/** The chip state plus the conversation it was taken for. */
data class ContextReadingDisplay(
    val state: ContextWindowUsageState = ContextWindowUsagePolicy.cleared(),
    val shownFor: ContextWindowUsageKey? = null,
)

/**
 * The [ContextWindowUsagePolicy] rules applied to a
 * streamed reading instead of a repository read, so every client shows the same thing:
 *
 *  - another conversation's reading is dropped the moment the focus moves;
 *  - a new number is taken only once the turn settles — the frame lands mid-turn, and the
 *    chip should not move while the reply is still streaming;
 *  - with no new number, the last good one stays; with none at all, the placeholder shows.
 */
fun ContextReadingDisplay.advance(inputs: ContextReadingInputs): ContextReadingDisplay {
    val kept = if (inputs.key.sameIdentityAs(shownFor)) state else ContextWindowUsagePolicy.cleared()
    val tokens = inputs.contextTokens
    val next = if (tokens != null && ContextWindowUsagePolicy.readable(inputs.key)) {
        ContextWindowUsagePolicy.readTotal(tokens, inputs.windowTokens)
    } else {
        kept
    }
    return ContextReadingDisplay(next, inputs.key)
}

/** Folds a stream of [ContextReadingInputs] into the chip state through [advance]. */
fun Flow<ContextReadingInputs>.contextUsageStates(): Flow<ContextWindowUsageState> =
    scan(ContextReadingDisplay()) { display, inputs -> display.advance(inputs) }
        .map { it.state }
        .distinctUntilChanged()

/**
 * The chip state with the conversation's real breakdown. While this is collected, [breakdown]
 * fetches the focused conversation's breakdown in the background (on open, and once each turn
 * settles), and the chip shows it in place of the streamed estimate while it is current: matched to
 * the reading on show, or loaded since that reading came in. A reading that has moved past it (a
 * settled turn whose refresh has not landed, or failed) shows instead. The card reads the same
 * [ContextBreakdownLoader.breakdowns], so the two never disagree. Without a [breakdown], the
 * streamed reading alone ([contextUsageStates]).
 */
fun Flow<ContextReadingInputs>.contextUsageStates(breakdown: ContextBreakdownLoader?): Flow<ContextWindowUsageState> {
    breakdown ?: return contextUsageStates()
    val inputs = this
    return channelFlow {
        launch { breakdown.follow(inputs.filter { it.key.settled }.map(ContextReadingInputs::breakdownRequest)) }
        combine(inputs.contextUsageStates(), inputs.map { it.key.breakdownKey() }.distinctUntilChanged(), breakdown.breakdowns) {
                reading, key, known ->
            MeterInputs(reading, key, key?.let(known::get))
        }.scan(MeterChoice()) { previous, next -> previous.advance(next) }
            .mapNotNull { it.shown }
            .distinctUntilChanged()
            .collect { send(it) }
    }
}

/** Matched to the reading on show, loaded since it came in, or the only figure there is. */
private fun ContextBreakdown.isCurrentFor(total: Int?, loadedAt: Int?): Boolean =
    total == null || reportedTotal == total || loadedAt == total

/** What the meter chooses between at one instant. */
private class MeterInputs(val reading: ContextWindowUsageState, val key: ContextBreakdownKey?, val cached: ContextBreakdown?)

/**
 * The meter's pick, and the reading that was on show when its breakdown arrived: a breakdown that
 * landed after the latest reading is newer than it, even when it was not matched to a total.
 */
private class MeterChoice(
    val shown: ContextWindowUsageState? = null,
    val key: ContextBreakdownKey? = null,
    val cached: ContextBreakdown? = null,
    val readingWhenLoaded: Int? = null,
) {
    fun advance(next: MeterInputs): MeterChoice {
        val total = next.reading.usage?.usedTokens
        val loadedAt = loadedAtFor(next, total)
        val current = next.cached?.takeIf { it.isCurrentFor(total, loadedAt) }
        return MeterChoice(current?.let { ContextWindowUsagePolicy.read(it.overview) } ?: next.reading, next.key, next.cached, loadedAt)
    }

    /** The reading on show when [next]'s breakdown arrived; unknown for a breakdown found already cached. */
    private fun loadedAtFor(next: MeterInputs, total: Int?): Int? {
        if (next.cached == null || next.key != key) return null
        return if (next.cached === cached) readingWhenLoaded else total
    }
}

/** The breakdown request for the focused conversation and its latest total; null with no agent. */
private fun ContextReadingInputs.breakdownRequest(): ContextBreakdownRequest? =
    key.breakdownKey()?.let { ContextBreakdownRequest(it.agentId, it.conversationId, contextTokens) }

private fun ContextWindowUsageKey.breakdownKey(): ContextBreakdownKey? =
    agentId?.takeIf { it.isNotBlank() }?.let { ContextBreakdownKey(AgentId(it), conversationId?.let(::ConversationId)) }
