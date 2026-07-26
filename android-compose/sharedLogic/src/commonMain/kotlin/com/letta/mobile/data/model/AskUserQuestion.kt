package com.letta.mobile.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Structured shape of an `AskUserQuestion` tool call's arguments and the answer
 * that closes it. See epic letta-mobile-vilsn.
 *
 * The letta-code CLI emits AskUserQuestion with:
 * `{ questions: [{ question, header?, multiSelect?, options: [{label, description?}] }] }`
 * and closes the call when the client returns the approval as
 * `Allow(updated_input = { …originalInput, answers: { [questionText]: answer } })`.
 */
@Serializable
data class AskUserQuestionSpec(
    val questions: List<AskUserQuestionItem> = emptyList(),
)

@Serializable
data class AskUserQuestionItem(
    val question: String = "",
    val header: String? = null,
    val multiSelect: Boolean = false,
    val options: List<AskUserQuestionOption> = emptyList(),
)

@Serializable
data class AskUserQuestionOption(
    val label: String = "",
    val description: String? = null,
)

object AskUserQuestion {
    const val ASK_USER_QUESTION_TOOL: String = "AskUserQuestion"

    /**
     * Internal transport prefix. The tool-approval submit chain (UI → repository
     * → Iroh admin_rpc → host controller) only threads a `reason: String?` end to
     * end; `updated_input` exists solely on the host-side `Allow` decision. Rather
     * than thread a new param through ~20 layers (shared by both clients + tests),
     * an AskUserQuestion answer rides the `reason` channel as this prefix followed
     * by the `updated_input` JSON, and [decodeAnswerReason] unpacks it at the one
     * terminal point both clients reach (DefaultAppServerController.submitApproval).
     * Follow-up: promote to a first-class param (letta-mobile-vilsn).
     */
    private const val ANSWER_REASON_PREFIX = "askuserquestion-answer"

    /** Tolerant parser: unknown keys ignored, missing fields defaulted. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Encode an AskUserQuestion answer [updatedInput] for transport over `reason`. */
    fun encodeAnswerReason(updatedInput: JsonObject): String = ANSWER_REASON_PREFIX + updatedInput.toString()

    /**
     * If [reason] carries an encoded AskUserQuestion answer, return its
     * `updated_input` object; otherwise null (a normal approval reason).
     */
    fun decodeAnswerReason(reason: String?): JsonObject? {
        if (reason == null || !reason.startsWith(ANSWER_REASON_PREFIX)) return null
        val payload = reason.substring(ANSWER_REASON_PREFIX.length)
        return runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    /**
     * Parse the AskUserQuestion tool-call arguments into a [AskUserQuestionSpec].
     *
     * [argumentsJson] is normally a JSON object string (`{"questions":[…]}`), but
     * some transports double-encode it as a JSON string containing that object.
     * Both are handled. Returns null when the payload has no usable questions.
     */
    fun parse(argumentsJson: String?): AskUserQuestionSpec? {
        if (argumentsJson.isNullOrBlank()) return null
        val obj = argumentsJson.toArgsObject() ?: return null
        val spec = runCatching { json.decodeFromJsonElement(AskUserQuestionSpec.serializer(), obj) }.getOrNull()
            ?: return null
        val usable = spec.questions.filter { it.question.isNotBlank() }
        return if (usable.isEmpty()) null else AskUserQuestionSpec(usable)
    }

    /**
     * Build the `updated_input` object that closes the call: the original
     * arguments with an `answers` map keyed by question text. Multi-select
     * answers are comma-joined, matching the freeform shape the CLI parses.
     *
     * @param answers question text -> selected option label(s) (already resolved,
     *   including any free-text "Other" value).
     */
    fun buildUpdatedInput(argumentsJson: String?, answers: Map<String, List<String>>): JsonObject {
        val original = argumentsJson?.toArgsObject() ?: JsonObject(emptyMap())
        val answersObj = buildJsonObject {
            for ((question, values) in answers) {
                val joined = values.filter { it.isNotBlank() }.joinToString(", ")
                if (joined.isNotEmpty()) put(question, joined)
            }
        }
        return buildJsonObject {
            for ((key, value) in original) {
                if (key != "answers") put(key, value)
            }
            put("answers", answersObj)
        }
    }

    private fun String.toArgsObject(): JsonObject? {
        val element = runCatching { Json.parseToJsonElement(this) }.getOrNull() ?: return null
        return when (element) {
            is JsonObject -> element
            is JsonPrimitive -> if (element.isString) {
                runCatching { Json.parseToJsonElement(element.content).jsonObject }.getOrNull()
            } else null
            else -> null
        }
    }
}
