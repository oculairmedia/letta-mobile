package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.VoiceText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads an OpenAI-compatible transcription body: `json` (`{"text": ...}`) or `verbose_json` (the
 * same, plus `segments` carrying `no_speech_prob` and `avg_logprob`). The confidence is kept only
 * when there is at least one segment and every segment carries both numbers; otherwise the
 * transcript has none, exactly as a plain `json` answer.
 */
internal object TranscriptResponse {
    private const val TEXT = "text"
    private const val SEGMENTS = "segments"
    private const val NO_SPEECH = "no_speech_prob"
    private const val AVG_LOGPROB = "avg_logprob"

    /** The transcript in [body], or null when it is not a JSON object with a string `text`. */
    fun parse(body: String): SpeechTranscript? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val text = root[TEXT] as? JsonPrimitive
        if (text == null || !text.isString) return null
        return SpeechTranscript(VoiceText(text.content.trim()), confidenceOf(root[SEGMENTS]))
    }

    private fun confidenceOf(segments: JsonElement?): RecognizerConfidence? {
        val list = (segments as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        val pairs = list.map { segment -> numbersOf(segment) ?: return null }
        return RecognizerConfidence(pairs.minOf { it.first }, pairs.maxOf { it.second })
    }

    private fun numbersOf(segment: JsonElement): Pair<Double, Double>? {
        val fields = segment as? JsonObject ?: return null
        val noSpeech = (fields[NO_SPEECH] as? JsonPrimitive)?.doubleOrNull ?: return null
        val logprob = (fields[AVG_LOGPROB] as? JsonPrimitive)?.doubleOrNull ?: return null
        return noSpeech to logprob
    }
}
