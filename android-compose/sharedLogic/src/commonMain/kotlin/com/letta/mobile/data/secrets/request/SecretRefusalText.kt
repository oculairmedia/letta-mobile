package com.letta.mobile.data.secrets.request

import kotlinx.serialization.KSerializer

/**
 * How a refusal is spelled in the admin RPC `error` text (contract M3): the code, or for
 * `invalid_request` the code, `:` and the problem (`invalid_request:purpose_blank`).
 */
object SecretRefusalText {
    private const val PROBLEM_SEPARATOR = ':'

    /** The `error` text of [refusal]. */
    fun of(refusal: SecretRequestRefusal): String {
        val code = codeOf(SecretServiceError.serializer(), refusal.error.ordinal)
        val problem = refusal.problem ?: return code
        return code + PROBLEM_SEPARATOR + codeOf(SecretRequestProblem.serializer(), problem.ordinal)
    }

    /** The refusal [text] spells, or null when it is not one of this contract's codes. */
    fun parse(text: String): SecretRequestRefusal? {
        val error = entryOf(SecretServiceError.serializer(), text.substringBefore(PROBLEM_SEPARATOR), SecretServiceError.entries)
            ?: return null
        if (PROBLEM_SEPARATOR !in text) return SecretRequestRefusal(error)
        val problem = entryOf(SecretRequestProblem.serializer(), text.substringAfter(PROBLEM_SEPARATOR), SecretRequestProblem.entries)
        return problem?.let { SecretRequestRefusal(error, it) }
    }

    private fun codeOf(serializer: KSerializer<*>, ordinal: Int): String = serializer.descriptor.getElementName(ordinal)

    private fun <E : Enum<E>> entryOf(serializer: KSerializer<E>, code: String, entries: List<E>): E? =
        entries.firstOrNull { codeOf(serializer, it.ordinal) == code }
}
