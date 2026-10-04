package com.letta.mobile.data.context

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

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
 * letta-mobile-r2zo8 / letta-mobile-0ofhc: the [ContextWindowUsagePolicy] rules applied to a
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
