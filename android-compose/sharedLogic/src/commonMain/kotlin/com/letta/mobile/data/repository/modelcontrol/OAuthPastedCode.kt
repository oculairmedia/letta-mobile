package com.letta.mobile.data.repository.modelcontrol

import io.ktor.http.Url
import io.ktor.http.parseQueryString

/**
 * What the user pasted after approving an authorization-code sign-in
 * the whole address the browser ended on, a
 * `code=…&state=…` query, `code#state`, or the bare code.
 */
internal data class PastedCode(val code: String, val state: String?) {
    override fun toString(): String = "PastedCode(<redacted>)"

    /** A pasted state that differs from the one this sign-in sent; no state on either side is no contradiction. */
    private fun contradicts(expectedState: String?): Boolean {
        val pastedState = state ?: return false
        return expectedState?.let { it != pastedState } ?: false
    }

    companion object {
        fun parse(pasted: String, expectedState: String?): PastedCode {
            val parsed = split(pasted.trim())
            if (parsed.code.isBlank()) throw ProviderSignInException("Paste the code from the sign-in page", SignInFailure.BAD_CODE)
            if (parsed.contradicts(expectedState)) {
                throw ProviderSignInException("That code is from a different sign-in; start again", SignInFailure.BAD_CODE)
            }
            return parsed
        }

        private fun split(value: String): PastedCode = when {
            value.startsWith("http://") || value.startsWith("https://") -> fromQuery(Url(value).encodedQuery)
            value.contains("code=") -> fromQuery(value.substringAfter('?'))
            value.contains('#') -> PastedCode(value.substringBefore('#'), value.substringAfter('#').ifBlank { null })
            else -> PastedCode(value, null)
        }

        private fun fromQuery(query: String): PastedCode {
            val params = parseQueryString(query)
            return PastedCode(params["code"].orEmpty(), params["state"])
        }
    }
}
