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
 * Dedupes by underlying model ID (handle suffix), prefers a canonical routing
 * handle, and fills known limits when the upstream catalog leaves them blank.
 */
object ModelCatalogNormalizer {
    data class KnownLimits(
        val contextWindow: Int,
        val maxOutputTokens: Int,
    )

    /**
     * Provider-prefix preference when several handles share one underlying model.
     * Prefer OpenAI-compatible LLMux dialects over Anthropic-shaped aliases and
     * over bare `lmstudio/` when all resolve to the same upstream model.
     */
    private val PROVIDER_RANK = listOf(
        "openai",
        "lc-openai",
        "anthropic",
        "lc-anthropic",
        "lmstudio",
        "lm_studio",
        "openrouter",
        "minimax",
        "xai",
        "groq",
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
     * Collapse duplicate provider-prefixed handles for the same underlying model.
     * Keeps the highest-ranked provider (or the entry with richer metadata).
     */
    fun dedupeByUnderlyingModel(models: List<LlmModel>): List<LlmModel> {
        if (models.size <= 1) return models
        val winners = LinkedHashMap<String, LlmModel>()
        val order = ArrayList<String>()
        for (model in models) {
            val key = underlyingModelId(
                model.handle?.takeIf { it.isNotBlank() } ?: model.id.ifBlank { model.name },
            ).lowercase()
            if (key.isEmpty()) {
                // Unkeyable — keep as-is under a unique slot.
                val unique = "unkeyed-${order.size}-${model.id}"
                winners[unique] = model
                order.add(unique)
                continue
            }
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

    private fun prefer(candidate: LlmModel, incumbent: LlmModel): Boolean {
        val candidateScore = richness(candidate) * 100 + providerScore(candidate)
        val incumbentScore = richness(incumbent) * 100 + providerScore(incumbent)
        return candidateScore > incumbentScore
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
