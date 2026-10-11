package com.letta.mobile.data.context.estimate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-cyh28: letta-code's `estimateLocalMessageTokens` (`local-context-estimate.ts`) over
 * one on-disk local message, so a section counted here is the number the server would count.
 *
 * Text, thinking and tool-call blocks count their characters (a tool call: its name plus its
 * serialized arguments); an image counts a flat [IMAGE_TOKENS]. A message's blocks live in
 * `content` (a string or an array) on disk, or in `parts` once a reader has normalized it; both
 * are accepted. Anything unreadable counts zero rather than failing the estimate.
 */
object LocalMessageTokens {
    /** letta-code's `IMAGE_TOKEN_ESTIMATE`. */
    const val IMAGE_TOKENS: Int = 1200

    fun of(message: JsonObject, estimator: TokenEstimator = CharsPerToken): Int {
        val content = message["content"]
        if (content is JsonPrimitive) return estimator.tokensForChars(content.contentOrNull.orEmpty().length.toLong())
        val blocks = (content as? JsonArray) ?: (message["parts"] as? JsonArray) ?: return 0
        val chars = blocks.sumOf(::blockChars)
        return estimator.tokensForChars(chars)
    }

    fun ofAll(messages: List<JsonObject>, estimator: TokenEstimator = CharsPerToken): Int =
        messages.sumOf { of(it, estimator) }

    private fun blockChars(element: JsonElement): Long {
        val block = element as? JsonObject ?: return 0L
        return when (block.text("type")) {
            "text" -> block.text("text").orEmpty().length.toLong()
            "thinking" -> block.text("thinking").orEmpty().length.toLong()
            "toolCall" -> block.text("name").orEmpty().length.toLong() + block["arguments"].serializedLength()
            // letta-code adds `IMAGE_TOKEN_ESTIMATE * 4` characters per image.
            "image" -> IMAGE_TOKENS * CHARS_PER_IMAGE_TOKEN
            else -> 0L
        }
    }

    private fun JsonElement?.serializedLength(): Long = when (this) {
        null -> 0L
        is JsonPrimitive -> if (isString) content.length.toLong() + 2 else content.length.toLong()
        else -> toString().length.toLong()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val CHARS_PER_IMAGE_TOKEN = 4L
}
