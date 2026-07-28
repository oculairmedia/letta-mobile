package com.letta.mobile.data.model

/**
 * Normalizes App Server / LLMux model listings after the native `list_models` path.
 *
 * Problems this closes (letta-mobile-o0atv):
 * - Multiple providers (`lmstudio`, `openai`/`lc-openai`, `anthropic`/`lc-anthropic`)
 *   pointing at the same LLMux endpoint produce duplicate handles for one model ID.
 * - LLMux omits context/output limits for some models (Grok, MiniMax) that still need
 *   dependable bounds for Letta full-context + tool turns.
 *
 * Dedupes only within the known LLMux alias provider set (never collapses distinct
 * routes such as `openai/gpt-4o` vs `azure/gpt-4o`), prefers a canonical routing
 * handle, and fills known limits when the upstream catalog leaves them blank.
 */
object ModelCatalogNormalizer {
    data class KnownLimits(
        val contextWindow: Int,
        val maxOutputTokens: Int,
    )

    /**
     * Providers that commonly alias the same LLMux upstream catalog entry.
     * Only entries whose prefixes are all in this set may collapse together.
     */
    private val LLMUX_ALIAS_PROVIDERS = setOf(
        "openai",
        "lc-openai",
        "anthropic",
        "lc-anthropic",
        "lmstudio",
        "lm_studio",
    )

    /**
     * Provider-prefix preference when several LLMux aliases share one underlying model.
     * Prefer OpenAI-compatible dialects over Anthropic-shaped aliases and over bare
     * `lmstudio/` when all resolve to the same upstream model.
     */
    private val PROVIDER_RANK = listOf(
        "openai",
        "lc-openai",
        "anthropic",
        "lc-anthropic",
        "lmstudio",
        "lm_studio",
    )

    /** Explicit limits for models whose LLMux catalog entries omit token metadata. */
    private val KNOWN_LIMITS: List<Pair<Regex, KnownLimits>> = listOf(
        Regex("""(?i)^minimax[-_]?m3$""") to KnownLimits(contextWindow = 200_000, maxOutputTokens = 16_384),
        Regex("""(?i)^cursor-grok""") to KnownLimits(contextWindow = 131_072, maxOutputTokens = 8_192),
        Regex("""(?i)^grok-""") to KnownLimits(contextWindow = 131_072, maxOutputTokens = 8_192),
    )

    fun underlyingModelId(handleOrId: String): String {
        val trimmed = handleOrId.trim()
        if (trimmed.isEmpty()) return trimmed
        val slash = trimmed.lastIndexOf('/')
        return if (slash >= 0 && slash < trimmed.lastIndex) trimmed.substring(slash + 1) else trimmed
    }

    fun providerPrefix(handleOrId: String): String {
        val trimmed = handleOrId.trim()
        val slash = trimmed.indexOf('/')
        return if (slash > 0) trimmed.substring(0, slash).lowercase() else ""
    }

    fun knownLimitsForUnderlyingId(underlyingId: String): KnownLimits? {
        val id = underlyingId.trim()
        if (id.isEmpty()) return null
        return KNOWN_LIMITS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(id) }?.second
    }

    fun knownLimitsForHandle(handleOrId: String?): KnownLimits? {
        if (handleOrId.isNullOrBlank()) return null
        return knownLimitsForUnderlyingId(underlyingModelId(handleOrId))
    }

    /** Fill missing context/output from known tables; never overwrite authoritative values. */
    fun enrichLimits(model: LlmModel): LlmModel {
        val handle = model.handle?.takeIf { it.isNotBlank() } ?: model.id
        val known = knownLimitsForHandle(handle) ?: return model
        return model.copy(
            contextWindow = model.contextWindow?.takeIf { it > 0 } ?: known.contextWindow,
            maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 } ?: known.maxOutputTokens,
            maxTokens = model.maxTokens?.takeIf { it > 0 } ?: known.maxOutputTokens,
        )
    }

    /**
     * Collapse LLMux-alias duplicates for the same underlying model.
     * Distinct provider routes (e.g. openai vs azure) are preserved.
     */
    fun dedupeByUnderlyingModel(models: List<LlmModel>): List<LlmModel> {
        if (models.size <= 1) return models
        val winners = LinkedHashMap<String, LlmModel>()
        val order = ArrayList<String>()
        for (model in models) {
            val key = dedupeKey(model)
            val existing = winners[key]
            if (existing == null) {
                winners[key] = model
                order.add(key)
            } else if (prefer(model, existing)) {
                winners[key] = model
            }
        }
        return order.mapNotNull { winners[it] }
    }

    fun normalize(models: List<LlmModel>): List<LlmModel> =
        dedupeByUnderlyingModel(models.map(::enrichLimits))

    private fun dedupeKey(model: LlmModel): String {
        val handle = model.handle?.takeIf { it.isNotBlank() }
            ?: model.id.ifBlank { model.name }
        val prefix = providerPrefix(handle).ifBlank { model.providerType.lowercase() }
        val underlying = underlyingModelId(handle).lowercase()
        return if (prefix in LLMUX_ALIAS_PROVIDERS && underlying.isNotEmpty()) {
            "llmux-alias:$underlying"
        } else {
            // Preserve distinct routes (azure vs openai, openrouter, custom, …).
            "route:${handle.ifBlank { "unkeyed-${model.id}" }.lowercase()}"
        }
    }

    /** Provider rank wins first; richness is only a same-rank tiebreaker. */
    private fun prefer(candidate: LlmModel, incumbent: LlmModel): Boolean {
        val candidateProvider = providerScore(candidate)
        val incumbentProvider = providerScore(incumbent)
        if (candidateProvider != incumbentProvider) {
            return candidateProvider > incumbentProvider
        }
        return richness(candidate) > richness(incumbent)
    }

    private fun richness(model: LlmModel): Int {
        var score = 0
        if ((model.contextWindow ?: 0) > 0) score += 2
        if ((model.maxOutputTokens ?: model.maxTokens ?: 0) > 0) score += 1
        if (!model.displayNameOverride.isNullOrBlank()) score += 1
        return score
    }

    private fun providerScore(model: LlmModel): Int {
        val prefix = providerPrefix(
            model.handle?.takeIf { it.isNotBlank() } ?: model.id,
        ).ifBlank { model.providerType.lowercase() }
        val rank = PROVIDER_RANK.indexOf(prefix)
        return if (rank < 0) 0 else (PROVIDER_RANK.size - rank)
    }
}
