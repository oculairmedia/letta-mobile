package com.letta.mobile.data.voice.speech

import com.letta.mobile.data.voice.VoiceText

/**
 * Joins the segments a chunker released together into fewer, longer ones for speech synthesis,
 * so a reply that arrives as a burst costs a few requests instead of one per sentence.
 *
 * With [firstAlone], the first segment is kept on its own: it is synthesised and heard while the
 * rest is still being requested, so first audio never waits for a batch. Every later segment
 * joins the one before it (with a space) while the result stays within [maxLength]; a segment
 * already longer than that is never cut or joined. Nothing waits for text that has not arrived:
 * only segments released at the same moment are joined, so a reply that streams one sentence at a
 * time is still spoken one sentence at a time.
 */
internal fun batchSegments(segments: List<VoiceText>, firstAlone: Boolean, maxLength: Int): List<VoiceText> {
    val batches = mutableListOf<String>()
    val joinable = if (firstAlone) 1 else 0
    for (segment in segments) {
        val last = batches.lastOrNull()?.takeIf { batches.size > joinable }
        if (last != null && last.length + 1 + segment.value.length <= maxLength) {
            batches[batches.lastIndex] = "$last ${segment.value}"
        } else {
            batches += segment.value
        }
    }
    return batches.map(::VoiceText)
}
