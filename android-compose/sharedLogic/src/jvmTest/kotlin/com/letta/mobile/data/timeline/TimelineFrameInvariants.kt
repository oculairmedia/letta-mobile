package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.TimelineFrameRecorder.Frame

/**
 * letta-mobile-dzt3g: the identity invariants every frame sequence from [TimelineFrameRecorder]
 * must hold. Empty means seamless; a violation names its frame so a failure reads as a timeline.
 *
 *  - one row per logical message: no message id appears twice in a frame
 *  - text only grows: a message never shrinks, and never re-reads an earlier text as its own tail
 *  - no reply stacks a copy of itself: no 40-character run of text occurs twice in one message
 *  - no key leaves and re-enters, and no message moves between keys ([handoverFlickers])
 */
internal fun timelineInvariantViolations(frames: List<Frame>): List<String> =
    rowIdentityViolations(frames) + textGrowthViolations(frames)

/** One row per message, no stacked copy, no key leaving and re-entering, no message changing key. */
internal fun rowIdentityViolations(frames: List<Frame>): List<String> =
    frames.flatMap(::duplicateMessages) + frames.flatMap(::stackedCopies) + handoverFlickers(frames)

/** A message's text only grows from one frame to the next. */
internal fun textGrowthViolations(frames: List<Frame>): List<String> = textRegressions(frames)

private val Frame.label: String get() = "frame $index [$step]"

private fun Frame.texts(): List<Pair<String, String>> =
    rows.flatMap { row -> row.messages.zip(row.contents) }

private fun duplicateMessages(frame: Frame): List<String> =
    frame.texts().groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        .map { (message, copies) -> "${frame.label}: $message has ${copies.size} rows" }

/**
 * A message that holds the same long run of text twice is a copy stacked onto itself: successive
 * cumulative snapshots appended to one another repeat each snapshot's words.
 */
private fun stackedCopies(frame: Frame): List<String> =
    frame.texts().filter { (_, text) -> text.repeatsALongRun() }
        .map { (message, _) -> "${frame.label}: $message stacks a copy of its own text" }

private fun String.repeatsALongRun(): Boolean {
    if (length < REPEATED_RUN_LENGTH * 2) return false
    val seen = HashSet<String>()
    return windowed(REPEATED_RUN_LENGTH).any { !seen.add(it) }
}

private fun textRegressions(frames: List<Frame>): List<String> {
    val held = mutableMapOf<String, String>()
    return frames.flatMap { frame ->
        frame.texts().mapNotNull { (message, text) ->
            held.put(message, text)?.let { before -> regression(frame, message, before, text) }
        }
    }
}

private fun regression(frame: Frame, message: String, before: String, after: String): String? = when {
    after.startsWith(before) -> null
    after.length < before.length -> "${frame.label}: $message shrank from ${before.length} to ${after.length} chars"
    after.endsWith(before) -> "${frame.label}: $message re-reads its earlier text as its tail"
    else -> null
}

private const val REPEATED_RUN_LENGTH = 40
