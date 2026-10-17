package com.letta.mobile.runtime.local

/**
 * letta-mobile-by4vm.121.16 follow-up: whether a request fits the engine's context window, checked
 * BEFORE LiteRT-LM sees it.
 *
 * LiteRT-LM 0.18.0 does not reliably fail a request that overflows its window: a modest overflow
 * returns "Input token ids are too long", but a large one (conversation history plus many tool
 * schemas, several times the window) corrupts the native heap and aborts the whole app process
 * (reproduced on the host with the litertlm-jvm 0.18.0 artifact: `corrupted size vs. prev_size`,
 * SIGABRT; on a Pixel 9 Pro: ~92 K chars of history and 21 tools into a 4,096-token window killed
 * the app on every send). The library exposes no tokenizer, so the bridge estimates
 * conservatively (fewer characters per token than Gemma's tokenizer averages) and answers a
 * request that may not fit with the loud, non-retryable overflow error instead of handing it on.
 * Nothing is trimmed: the request either goes through whole or is refused with the numbers.
 */
internal object OnDeviceContextBudget {
    /** Conservative: Gemma's 262 K-entry vocabulary averages ~4 chars per token on English and JSON. */
    private const val CHARS_PER_TOKEN = 3

    /** Chat-template turn markers and role names around each message. */
    private const val TOKENS_PER_MESSAGE = 8

    /** Gemma 3n's vision encoder spends 256 tokens per image; rounded up. */
    private const val TOKENS_PER_IMAGE = 300

    /** The template's tool declaration wrapper around each schema. */
    private const val TOKENS_PER_TOOL = 16

    /** The prompt's estimated token count: every text, tool schema, tool call and result, and image. */
    fun estimatePromptTokens(request: OnDeviceChatRequest): Int {
        val messages = request.history + request.latest
        val chars = (request.systemInstruction?.length ?: 0) +
            request.tools.sumOf { it.functionJson.length } +
            messages.sumOf { message ->
                message.text.length +
                    message.toolCalls.sumOf { it.name.length + it.argumentsJson.length } +
                    message.toolResults.sumOf { it.name.length + it.content.length }
            }
        return ceilDiv(chars, CHARS_PER_TOKEN) +
            messages.size * TOKENS_PER_MESSAGE +
            request.images.size * TOKENS_PER_IMAGE +
            request.tools.size * TOKENS_PER_TOOL
    }

    /**
     * The overflow rejection when the prompt plus the reply's output budget may not fit
     * [contextTokens] (the engine's window, Settings "Max tokens"); null when it fits.
     */
    fun overflow(request: OnDeviceChatRequest, maxOutputTokens: Int, contextTokens: Int): OnDeviceRequestRejected? {
        val prompt = estimatePromptTokens(request)
        if (prompt + maxOutputTokens <= contextTokens) return null
        return OnDeviceRequestRejected(
            "on_device_context_overflow",
            "The request needs about $prompt prompt tokens plus $maxOutputTokens for the reply, more than the on-device " +
                "model's $contextTokens-token context window (${request.history.size + 1} messages, ${request.tools.size} tools). " +
                "Use the compact profile for this agent, raise Settings > Max tokens, or start a new conversation.",
        )
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
