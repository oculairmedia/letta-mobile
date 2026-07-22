package com.letta.mobile.data.timeline.headless

/**
 * Pure render-control state for smoothing streamed text reveal without owning canonical timeline data.
 *
 * Callers append or replace the canonical buffer from TimelineStreamReducer/TimelineRepository output,
 * then reveal at whatever cadence and chunk size the UI wants. The invariant is that [revealed] is
 * always a prefix of [buffer].
 */
class HeadlessStreamingRevealState(
    initialBuffer: String = "",
    initialRevealedCount: Int = 0,
    private val defaultRevealCodePoints: Int = DEFAULT_REVEAL_CODE_POINTS,
) {
    var buffer: String = initialBuffer
        private set

    var revealed: String = initialBuffer.takePrefixCodePoints(initialRevealedCount)
        private set

    var phase: HeadlessStreamingRevealPhase = if (revealed.length < buffer.length) {
        HeadlessStreamingRevealPhase.REVEALING
    } else {
        HeadlessStreamingRevealPhase.IDLE
    }
        private set

    var sourceComplete: Boolean = false
        private set

    val pending: String
        get() = buffer.substring(revealed.length)

    init {
        require(defaultRevealCodePoints > 0) { "defaultRevealCodePoints must be positive" }
        require(initialRevealedCount >= 0) { "initialRevealedCount must not be negative" }
        ensurePrefixInvariant()
        updatePhaseAfterContentChange()
    }

    fun append(chunk: String): HeadlessStreamingRevealSnapshot {
        if (chunk.isNotEmpty()) {
            buffer += chunk
        }
        updatePhaseAfterContentChange()
        return snapshot()
    }

    fun replaceBuffer(canonicalContent: String): HeadlessStreamingRevealSnapshot {
        buffer = canonicalContent
        if (!buffer.startsWith(revealed)) {
            revealed = revealed.commonPrefixWith(buffer)
        }
        updatePhaseAfterContentChange()
        return snapshot()
    }

    fun revealNext(maxCodePoints: Int = defaultRevealCodePoints): HeadlessStreamingRevealSnapshot {
        require(maxCodePoints > 0) { "maxCodePoints must be positive" }
        if (phase == HeadlessStreamingRevealPhase.STOPPED || revealed.length == buffer.length) {
            updatePhaseAfterContentChange()
            return snapshot()
        }
        val nextCount = pending.takePrefixCodePoints(maxCodePoints).length
        revealed = buffer.substring(0, revealed.length + nextCount)
        updatePhaseAfterContentChange()
        return snapshot()
    }

    fun skipToEnd(): HeadlessStreamingRevealSnapshot {
        revealed = buffer
        updatePhaseAfterContentChange()
        return snapshot()
    }

    fun stop(): HeadlessStreamingRevealSnapshot {
        phase = HeadlessStreamingRevealPhase.STOPPED
        return snapshot()
    }

    fun resume(): HeadlessStreamingRevealSnapshot {
        if (phase == HeadlessStreamingRevealPhase.STOPPED) {
            updatePhaseAfterContentChange(forceActive = true)
        }
        return snapshot()
    }

    fun markSourceComplete(): HeadlessStreamingRevealSnapshot {
        sourceComplete = true
        updatePhaseAfterContentChange()
        return snapshot()
    }

    fun snapshot(): HeadlessStreamingRevealSnapshot = HeadlessStreamingRevealSnapshot(
        buffer = buffer,
        revealed = revealed,
        phase = phase,
        pending = pending,
        sourceComplete = sourceComplete,
    )

    private fun updatePhaseAfterContentChange(forceActive: Boolean = false) {
        ensurePrefixInvariant()
        if (!forceActive && phase == HeadlessStreamingRevealPhase.STOPPED) {
            return
        }
        phase = when {
            revealed.length < buffer.length -> HeadlessStreamingRevealPhase.REVEALING
            sourceComplete -> HeadlessStreamingRevealPhase.DONE
            buffer.isEmpty() -> HeadlessStreamingRevealPhase.IDLE
            else -> HeadlessStreamingRevealPhase.WAITING
        }
    }

    private fun ensurePrefixInvariant() {
        check(buffer.startsWith(revealed)) { "revealed text must be a prefix of buffer" }
    }

    private companion object {
        const val DEFAULT_REVEAL_CODE_POINTS = 8
    }
}

enum class HeadlessStreamingRevealPhase {
    IDLE,
    REVEALING,
    WAITING,
    DONE,
    STOPPED,
}

data class HeadlessStreamingRevealSnapshot(
    val buffer: String,
    val revealed: String,
    val phase: HeadlessStreamingRevealPhase,
    val pending: String,
    val sourceComplete: Boolean,
)

private fun String.takePrefixCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0 || isEmpty()) return ""
    var codePoints = 0
    var index = 0
    while (index < length && codePoints < maxCodePoints) {
        index += codePointWidthAt(index)
        codePoints += 1
    }
    return substring(0, index)
}

private fun String.codePointWidthAt(index: Int): Int =
    if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1

private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'

private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'

private fun String.commonPrefixWith(other: String): String {
    var index = 0
    var lastBoundary = 0
    while (index < length && index < other.length && this[index] == other[index]) {
        val width = codePointWidthAt(index)
        if (index + width > length || index + width > other.length) break
        if (width == 2 && other.codePointWidthAt(index) != 2) break
        index += width
        lastBoundary = index
    }
    return substring(0, lastBoundary)
}
