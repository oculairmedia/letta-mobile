package com.letta.mobile.data.runtime

/**
 * User-supplied configuration for an OpenAI-compatible endpoint (vLLM,
 * llama.cpp, Ollama, LM Studio, etc.) that the BUNDLED local letta-code
 * runtime should use as its model provider.
 *
 * First-pass scope (see letta-mobile bead "Bundled runtime has no in-app
 * provider configuration"): a single custom/local OpenAI-compatible
 * endpoint with an optional API key. No OAuth flows, no per-vendor
 * provider catalog.
 *
 * [apiKey] is intentionally nullable/blankable — many local servers
 * (Ollama, LM Studio, llama.cpp) don't require one.
 */
data class LocalRuntimeProviderConfig(
    val baseUrl: String,
    val apiKey: String? = null,
) {
    init {
        require(isValidBaseUrl(baseUrl)) {
            "baseUrl must be an http:// or https:// URL with a host, was: \"$baseUrl\""
        }
    }

    companion object {
        /**
         * Minimal shape check for an http(s) base URL — enough to catch
         * empty strings, missing schemes, and bare "http://" with no host,
         * without pulling in a platform-specific URL parser.
         */
        fun isValidBaseUrl(candidate: String): Boolean {
            val trimmed = candidate.trim()
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator <= 0) return false
            val scheme = trimmed.substring(0, schemeSeparator).lowercase()
            if (scheme != "http" && scheme != "https") return false
            val remainder = trimmed.substring(schemeSeparator + 3)
            return remainder.isNotBlank() && !remainder.startsWith("/") && !remainder.startsWith(" ")
        }
    }
}

/**
 * What's currently configured for the local runtime's OpenAI-compatible
 * provider, as read back from `providers/auth.json`. Never carries the
 * actual API key — only whether one is set — so it's safe to surface in
 * UI state and logs.
 */
data class LocalRuntimeProviderStatus(
    val baseUrl: String?,
    val hasApiKey: Boolean,
) {
    val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()
}
