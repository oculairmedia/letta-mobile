package com.letta.mobile.data.secrets.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why an agent's request is refused before it becomes pending. Stable wire codes. */
@Serializable
enum class SecretRequestProblem {
    /** The purpose is empty or only whitespace. */
    @SerialName("purpose_blank")
    PURPOSE_BLANK,

    /** The purpose is longer than [SecretRequestLimits.MAX_PURPOSE_LENGTH] after trimming. */
    @SerialName("purpose_too_long")
    PURPOSE_TOO_LONG,

    /**
     * The purpose holds markup, a link, a line break, an invisible or control character, or a word
     * that mixes Latin letters with Greek or Cyrillic look-alikes.
     */
    @SerialName("purpose_not_plain_text")
    PURPOSE_NOT_PLAIN_TEXT,

    /** The item name breaks the item grammar. */
    @SerialName("item_malformed")
    ITEM_MALFORMED,

    /** The field name breaks the field grammar. */
    @SerialName("field_malformed")
    FIELD_MALFORMED,

    /** The item lies outside the scope the host allows agents to request. */
    @SerialName("item_outside_scope")
    ITEM_OUTSIDE_SCOPE,

    /** The agent already has [SecretRequestLimits.MAX_PENDING_PER_AGENT] pending requests. */
    @SerialName("too_many_pending")
    TOO_MANY_PENDING,

    /**
     * The agent already created [SecretRequestLimits.MAX_CREATED_PER_WINDOW] requests within the
     * last [SecretRequestLimits.CREATION_WINDOW_MS]; it may ask again once the oldest leaves the window.
     */
    @SerialName("too_many_requests")
    TOO_MANY_REQUESTS,
}

/** The outcome of validating an agent's request. */
sealed interface SecretRequestValidation {
    /** The request is acceptable; [request] holds the trimmed purpose. */
    data class Valid(
        /** The normalized request the host stores. */
        val request: AgentSecretRequest,
    ) : SecretRequestValidation

    /** The request is refused for [problem]. */
    data class Invalid(
        /** Why it is refused. */
        val problem: SecretRequestProblem,
    ) : SecretRequestValidation

    /** Validation rules. */
    companion object {
        private val FORBIDDEN_CHARACTERS = setOf('<', '>', '[', ']', '`')
        private val FORBIDDEN_SUBSTRINGS = listOf("://", "www.", "mailto:")
        private val INVISIBLE_CATEGORIES = setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR,
            CharCategory.PRIVATE_USE,
            CharCategory.UNASSIGNED,
            CharCategory.SURROGATE,
        )

        /**
         * Checks [request] against the grammar and limits, with items confined to [itemScope]
         * when the host sets one. The purpose is trimmed; nothing else is normalized.
         */
        fun validate(request: AgentSecretRequest, itemScope: VaultItemName? = null): SecretRequestValidation {
            val purpose = request.purpose.value.trim()
            val problem = purposeProblem(purpose) ?: targetProblem(request, itemScope)
            return problem?.let(::Invalid) ?: Valid(request.copy(purpose = SecretPurpose(purpose)))
        }

        /** Why [purpose] (already trimmed) is not acceptable plain text, or null when it is. */
        fun purposeProblem(purpose: String): SecretRequestProblem? = when {
            purpose.isEmpty() -> SecretRequestProblem.PURPOSE_BLANK
            purpose.length > SecretRequestLimits.MAX_PURPOSE_LENGTH -> SecretRequestProblem.PURPOSE_TOO_LONG
            !isPlainText(purpose) -> SecretRequestProblem.PURPOSE_NOT_PLAIN_TEXT
            else -> null
        }

        private fun targetProblem(request: AgentSecretRequest, itemScope: VaultItemName?): SecretRequestProblem? = when {
            !request.vaultItemName.isWellFormed -> SecretRequestProblem.ITEM_MALFORMED
            !request.fieldName.isWellFormed -> SecretRequestProblem.FIELD_MALFORMED
            itemScope?.covers(request.vaultItemName) == false -> SecretRequestProblem.ITEM_OUTSIDE_SCOPE
            else -> null
        }

        private fun isPlainText(text: String): Boolean {
            val lowered = text.lowercase()
            return listOf(
                text.none { it in FORBIDDEN_CHARACTERS },
                FORBIDDEN_SUBSTRINGS.none(lowered::contains),
                text.none { it.category in INVISIBLE_CATEGORIES },
                !text.startsWith('#'),
                !hasMixedScriptWord(text),
            ).all { it }
        }

        /**
         * Whether one word (a run of letters) mixes Latin letters with Greek or Cyrillic ones, the
         * look-alike trick that spells a familiar name with foreign letters (a Latin word with one
         * Cyrillic U+0430 in place of "a"). A sentence may use several scripts, each word one of
         * them. The cost is a word that really mixes them, such as the Greek mu U+03BC glued to a
         * unit ("s"); the micro sign U+00B5 is not Greek and is fine.
         */
        private fun hasMixedScriptWord(text: String): Boolean {
            var latin = false
            var lookalike = false
            for (char in text) {
                if (!char.isLetter()) {
                    latin = false
                    lookalike = false
                    continue
                }
                latin = latin || LATIN.any { char in it }
                lookalike = lookalike || LOOKALIKE.any { char in it }
                if (latin && lookalike) return true
            }
            return false
        }

        /** Basic Latin, Latin-1 and Latin Extended-A/B letters, and Latin Extended Additional. */
        private val LATIN = listOf('A'..'Z', 'a'..'z', '\u00C0'..'\u024F', '\u1E00'..'\u1EFF')

        /** Greek (with extended), and Cyrillic (with supplement and extensions A to C). */
        private val LOOKALIKE = listOf(
            '\u0370'..'\u03FF',
            '\u1F00'..'\u1FFF',
            '\u0400'..'\u052F',
            '\u1C80'..'\u1C8F',
            '\u2DE0'..'\u2DFF',
            '\uA640'..'\uA69F',
        )
    }
}
