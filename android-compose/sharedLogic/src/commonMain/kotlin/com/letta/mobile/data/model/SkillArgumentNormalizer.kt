package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Normalized skill invocation info extracted from a tool call's arguments.
 *
 * @property skillName The skill identifier (e.g. "searxng", "asus-router").
 * @property normalizedArguments The arguments with skill-invocation wrapper
 *   stripped, suitable for display as the tool card's argument detail. When
 *   the original arguments were not a skill wrapper, this is the original
 *   string unchanged.
 */
data class NormalizedSkillInvocation(
    val skillName: String,
    val normalizedArguments: String,
)

/**
 * Bounded skill-argument normalizer for tool-call projection.
 *
 * Skill invocations arrive with arguments in one of three shapes:
 *  1. Direct object: `{"skill":"searxng","query":"..."}`
 *  2. Single JSON-string wrapper: `"{\"skill\":\"searxng\",\"query\":\"...\"}"`
 *  3. Double JSON-string wrapper: a JSON string whose content is itself a
 *     JSON string containing the actual object.
 *
 * This helper unwraps up to two JSON-string layers, extracts the `skill`
 * field, and returns the remaining payload (without `skill`) as the
 * normalized arguments for display. Unknown or malformed input returns null
 * so callers fall back to the raw tool name/arguments — the tool call stays
 * visible, just without the friendly "Skill · <name>" label.
 *
 * Platform-neutral: lives in commonMain, uses only kotlinx.serialization.
 */
object SkillArgumentNormalizer {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalize a skill tool-call argument payload.
     *
     * Returns null when the payload is not a skill invocation (no `skill`
     * field at any unwrap level) or is malformed — the caller should render
     * the tool call with its original name and arguments.
     */
    fun normalize(arguments: String): NormalizedSkillInvocation? {
        if (arguments.isBlank()) return null
        val obj = unwrapJsonObject(arguments, depth = 0) ?: return null
        val skillField = obj["skill"] as? JsonPrimitive ?: return null
        val skillName = skillField.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

        // Re-serialize without the `skill` key for the display arguments.
        val remaining = JsonObject(obj.filterKeys { it != "skill" })
        val normalizedArgs = if (remaining.isEmpty()) {
            ""
        } else {
            runCatching { json.encodeToString(JsonObject.serializer(), remaining) }.getOrDefault(arguments)
        }
        return NormalizedSkillInvocation(skillName = skillName, normalizedArguments = normalizedArgs)
    }

    /**
     * Unwrap a JSON value up to [depth] string-wrapped layers until we reach
     * a JsonObject. depth=0 means "accept the value as-is if it's already an
     * object, otherwise unwrap one string layer"; each recursive step peels
     * one string wrapper. We cap at two wrappers (depth 0, 1, 2) — deeper
     * nesting is treated as malformed.
     */
    private fun unwrapJsonObject(raw: String, depth: Int): JsonObject? {
        if (depth > 2) return null
        return runCatching {
            val element = json.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> element
                is JsonPrimitive -> {
                    val content = element.contentOrNull ?: return null
                    unwrapJsonObject(content, depth + 1)
                }
                else -> null
            }
        }.getOrNull()
    }
}
