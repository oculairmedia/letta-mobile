package com.letta.mobile.runtime.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * letta-mobile-by4vm.121: the bounds one on-device generation runs under.
 *
 * A small on-device model (gemma-3n E2B on CPU) can fall into a repetition loop and, unbounded,
 * generates until the engine's context fills: many minutes during which the single engine serves
 * nothing else. Every bound here ends a run early with whatever was produced.
 */
data class OnDeviceGenerationLimits(
    /** Output cap in estimated tokens (the LiteRT-LM API has no output cap of its own). */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    /** Wall-clock backstop for one request, prefill included. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Output text that ends the reply where it starts (Gemma's turn markers, plus the request's `stop`). */
    val stopSequences: List<String> = DEFAULT_STOP_SEQUENCES,
    /** The request's own `max_completion_tokens`/`max_tokens`; null when it set none. */
    val requestedMaxOutputTokens: Int? = null,
) {
    /** Whether [maxOutputTokens] is the client's own limit (not the bridge's lower ceiling). */
    val capIsRequested: Boolean get() = requestedMaxOutputTokens != null && requestedMaxOutputTokens <= maxOutputTokens

    companion object {
        /** Long enough for a chat reply, short enough that a loop on CPU ends in about a minute. */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024

        /** Below the client's own 300 s header timeout (Node fetch), so the bridge answers first. */
        const val DEFAULT_TIMEOUT_MS = 180_000L

        /**
         * Gemma's turn markers: a model that writes one is starting the next turn of the
         * conversation itself, the commonest runaway. (The transcript role prefixes the bridge used
         * to stop on are gone with its hand-made transcript: the model's own chat template renders
         * the conversation now, by4vm.121.16.)
         */
        val DEFAULT_STOP_SEQUENCES = listOf(
            "<end_of_turn>",
            "<start_of_turn>",
            "<eos>",
        )

        /**
         * The request's own `max_completion_tokens`/`max_tokens` (the agent's model setting) can
         * lower the cap, never raise it past [DEFAULT_MAX_OUTPUT_TOKENS] or the engine's context
         * ([EmbeddedLettaCodeModelSelection.maxTokens], the Settings "Max tokens" field).
         */
        fun forRequest(
            request: JsonObject,
            modelSelection: EmbeddedLettaCodeModelSelection,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): OnDeviceGenerationLimits {
            val requested = request.positiveInt("max_completion_tokens") ?: request.positiveInt("max_tokens")
            val ceiling = minOf(DEFAULT_MAX_OUTPUT_TOKENS, modelSelection.maxTokens.coerceAtLeast(1))
            return OnDeviceGenerationLimits(
                maxOutputTokens = requested?.coerceAtMost(ceiling) ?: ceiling,
                timeoutMs = timeoutMs,
                stopSequences = DEFAULT_STOP_SEQUENCES + request.stopSequences(),
                requestedMaxOutputTokens = requested,
            )
        }

        /** OpenAI `stop`: one string or a list of them. */
        private fun JsonObject.stopSequences(): List<String> = when (val stop = this["stop"]) {
            is JsonPrimitive -> listOfNotNull(stop.contentOrNull?.takeIf { stop.isString && it.isNotEmpty() })
            is JsonArray -> stop.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) }
            else -> emptyList()
        }

        private fun JsonObject.positiveInt(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }
}

/** Why a generation ended before the model finished on its own. */
enum class OnDeviceStopReason(val wire: String) {
    MaxTokens("max_tokens"),
    StopSequence("stop_sequence"),
    Repetition("repetition"),
    Timeout("timeout"),
    ClientDisconnected("client_disconnected"),
}

/**
 * One on-device generation: the engine streams chunks into [accept] and stops when it returns
 * false; the bridge [cancel]s it when the client goes away or the timeout fires, which runs the
 * engine's native cancel registered with [onCancel]. Thread-safe: chunks arrive on the engine's
 * callback thread, cancels on the bridge's watcher threads.
 */
class OnDeviceGeneration(
    val limits: OnDeviceGenerationLimits = OnDeviceGenerationLimits(),
) {
    private val lock = Any()
    private val output = StringBuilder()
    private var chunks = 0
    private var cancelAction: (() -> Unit)? = null
    private var finished = false

    @Volatile var stopReason: OnDeviceStopReason? = null
        private set

    val isStopped: Boolean get() = stopReason != null

    /** The guarded output: cut at a stop sequence or a repetition loop. */
    val text: String get() = synchronized(lock) { output.toString() }

    /** Registers the engine's native cancel; runs it at once when already stopped. */
    fun onCancel(action: () -> Unit) {
        val runNow = synchronized(lock) {
            cancelAction = action
            stopReason != null && !finished
        }
        if (runNow) runCatching(action)
    }

    /** Appends [chunk]; false when the generation must stop (a limit hit, or cancelled). */
    fun accept(chunk: String): Boolean = synchronized(lock) {
        if (stopReason != null || finished) return false
        chunks += 1
        output.append(chunk)
        val reason = cutAtStopSequence() ?: cutRepetition() ?: tokenCapReached()
        if (reason != null) stopReason = reason
        reason == null
    }

    /**
     * Stops the generation for [reason] from outside (disconnect, timeout). No-op once it ended or
     * already stopped; returns true when this call stopped it.
     */
    fun cancel(reason: OnDeviceStopReason): Boolean {
        val action = synchronized(lock) {
            if (stopReason != null || finished) return false
            stopReason = reason
            cancelAction
        }
        action?.let { runCatching(it) }
        return true
    }

    /** The engine returned: later cancels (the socket closing after the reply) are no-ops. */
    fun finish() {
        synchronized(lock) { finished = true }
    }

    private fun tokenCapReached(): OnDeviceStopReason? =
        OnDeviceStopReason.MaxTokens.takeIf { estimatedTokens() >= limits.maxOutputTokens }

    /** Chunks are roughly tokens; chars/4 covers an engine that delivers bigger chunks. */
    private fun estimatedTokens(): Int = maxOf(chunks, (output.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)

    private fun cutAtStopSequence(): OnDeviceStopReason? {
        val index = limits.stopSequences
            .mapNotNull { stop -> output.indexOf(stop).takeIf { it >= 0 } }
            .minOrNull() ?: return null
        output.setLength(index)
        return OnDeviceStopReason.StopSequence
    }

    private fun cutRepetition(): OnDeviceStopReason? {
        val keep = repetitionCutIndex(output) ?: return null
        output.setLength(keep)
        return OnDeviceStopReason.Repetition
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4
    }
}

/**
 * When [text] ends in a short cycle repeated back to back, the length that keeps the text before
 * the loop plus one copy of the cycle; null otherwise. A cycle of `p` characters must repeat at
 * least `max(MIN_REPEATS, ceil(MIN_LOOP_CHARS / p))` times, so "ha ha" or a doubled word is not a
 * loop, while "the the the …" or a paragraph said four times is. Cheap: on ordinary text each
 * period fails within a few comparisons.
 */
internal fun repetitionCutIndex(text: CharSequence): Int? {
    val length = text.length
    for (period in 1..minOf(MAX_LOOP_PERIOD, length / MIN_REPEATS)) {
        val span = period * maxOf(MIN_REPEATS, (MIN_LOOP_CHARS + period - 1) / period)
        if (span <= length && isPeriodicTail(text, period, span)) return loopStart(text, period, length - span) + period
    }
    return null
}

/** Where the loop of [period] that covers [tailStart].. really begins: whole copies, then a partial one. */
private fun loopStart(text: CharSequence, period: Int, tailStart: Int): Int {
    var start = tailStart
    while (start - period >= 0 && isSameBlock(text, start - period, start, period)) start -= period
    // The model can drift into the loop mid-cycle: extend back over a partial copy too.
    while (start > 0 && text[start - 1] == text[start - 1 + period]) start -= 1
    return start
}

private fun isPeriodicTail(text: CharSequence, period: Int, span: Int): Boolean {
    val end = text.length
    for (i in end - span until end - period) {
        if (text[i] != text[i + period]) return false
    }
    return true
}

private fun isSameBlock(text: CharSequence, a: Int, b: Int, period: Int): Boolean {
    for (i in 0 until period) {
        if (text[a + i] != text[b + i]) return false
    }
    return true
}

private const val MAX_LOOP_PERIOD = 400
private const val MIN_REPEATS = 4
private const val MIN_LOOP_CHARS = 160
