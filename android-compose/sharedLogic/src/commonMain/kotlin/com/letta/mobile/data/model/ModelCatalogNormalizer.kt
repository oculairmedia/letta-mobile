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
 * Dedupes only within the known LLMux alias provider set when routing provenance
 * confirms a shared endpoint (never collapses distinct BYOK/custom routes such as
 * two `openai/...` entries with different `modelEndpoint` / `providerName`),
 * prefers a canonical routing handle, and fills known limits when the upstream
 * catalog leaves them blank.
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

    private val LLMUX_ROUTE_HINTS = listOf("llmux", "litellm", "lmstudio", "lm_studio")

    /**
     * Explicit limits for models whose LLMux catalog entries omit token metadata.
     * Match only verified IDs / known LLMux aliases — never an entire evolving family
     * (e.g. `grok-code-fast-1` is 256K and must not inherit 131K defaults).
     */
    private val KNOWN_LIMITS: List<Pair<Regex, KnownLimits>> = listOf(
        Regex("""(?i)^minimax[-_]?m3$""") to KnownLimits(contextWindow = 200_000, maxOutputTokens = 16_384),
        // Verified LLMux Cursor Grok aliases (e.g. cursor-grok-4.5-high-fast).
        Regex("""(?i)^cursor-grok""") to KnownLimits(contextWindow = 131_072, maxOutputTokens = 8_192),
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
        if (providerPrefix(handleOrId) !in LLMUX_ALIAS_PROVIDERS) return null
        return knownLimitsForUnderlyingId(underlyingModelId(handleOrId))
    }

    fun knownLimitsForModel(model: LlmModel): KnownLimits? {
        val handle = model.handle?.takeIf { it.isNotBlank() } ?: model.id
        val prefix = providerPrefix(handle).ifBlank { model.providerType.trim().lowercase() }
        if (prefix !in LLMUX_ALIAS_PROVIDERS || !hasLlmuxProvenance(model)) return null
        return knownLimitsForUnderlyingId(underlyingModelId(handle))
    }

    /** Fill missing context/output from known tables; never overwrite authoritative values. */
    fun enrichLimits(model: LlmModel): LlmModel {
        val known = knownLimitsForModel(model) ?: return model
        return model.copy(
            contextWindow = model.contextWindow?.takeIf { it > 0 } ?: known.contextWindow,
            maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 } ?: known.maxOutputTokens,
            maxTokens = model.maxTokens?.takeIf { it > 0 } ?: known.maxOutputTokens,
        )
    }

    /**
     * Collapse LLMux-alias duplicates for the same underlying model + routing identity.
     * Distinct provider routes and distinct endpoints are preserved.
     */
    fun dedupeByUnderlyingModel(models: List<LlmModel>): List<LlmModel> =
        normalizePaired(models.map { Unit to it }).map { it.second }

    /**
     * Like [normalize], but keeps an associated source payload paired with the
     * winning model so callers do not re-associate by handle alone.
     */
    fun <T> normalizePaired(items: List<Pair<T, LlmModel>>): List<Pair<T, LlmModel>> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) {
            val (meta, model) = items.single()
            return listOf(meta to enrichLimits(model))
        }
        val winners = LinkedHashMap<String, Pair<T, LlmModel>>()
        val order = ArrayList<String>()
        for ((meta, rawModel) in items) {
            val key = dedupeKey(rawModel)
            val existing = winners[key]
            if (existing == null) {
                winners[key] = meta to rawModel
                order.add(key)
            } else {
                val candidateWins = prefer(rawModel, existing.second)
                val winnerMeta = if (candidateWins) meta else existing.first
                val winnerModel = if (candidateWins) rawModel else existing.second
                val alternate = if (candidateWins) existing.second else rawModel
                winners[key] = winnerMeta to mergeMissingMetadata(winnerModel, alternate)
            }
        }
        return order.mapNotNull { key ->
            winners[key]?.let { (meta, model) -> meta to enrichLimits(model) }
        }
    }

    fun normalize(models: List<LlmModel>): List<LlmModel> =
        normalizePaired(models.map { Unit to it }).map { it.second }

    private fun dedupeKey(model: LlmModel): String {
        val handle = model.handle?.takeIf { it.isNotBlank() }
            ?: model.id.ifBlank { model.name }
        val prefix = providerPrefix(handle).ifBlank { model.providerType.lowercase() }
        val underlying = underlyingModelId(handle)
        return if (prefix in LLMUX_ALIAS_PROVIDERS && underlying.isNotEmpty()) {
            // Only collapse when routing provenance agrees (shared / identical endpoint).
            "llmux-alias:$underlying:${routingIdentity(model)}"
        } else {
            // Preserve distinct routes (azure vs openai, openrouter, custom, …).
            "route:${handle.ifBlank { "unkeyed-${model.id}" }}:${routingIdentity(model)}"
        }
    }

    /**
     * Routing provenance used to decide whether two LLMux-alias handles share an
     * endpoint. Distinct `modelEndpoint` / `providerName` combinations stay separate;
     * rows with no endpoint identity (typical App Server presentation) share the
     * `shared` bucket so openai/lmstudio/anthropic aliases still collapse.
     */
    private fun routingIdentity(model: LlmModel): String {
        val endpoint = model.modelEndpoint
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
        val providerName = model.providerName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return if (endpoint != null || providerName != null) {
            "ep:${endpoint.orEmpty()}|pn:${providerName.orEmpty()}"
        } else {
            "shared"
        }
    }

    private fun hasLlmuxProvenance(model: LlmModel): Boolean {
        if (model.providerCategory.equals("byok", ignoreCase = true)) return false
        val routeMetadata = listOfNotNull(
            model.providerName,
            model.modelEndpoint,
            model.modelEndpointType,
        )
        if (routeMetadata.isEmpty()) return true
        return routeMetadata.any { value ->
            val normalized = value.lowercase()
            LLMUX_ROUTE_HINTS.any { it in normalized }
        }
    }

    private fun mergeMissingMetadata(preferred: LlmModel, alternate: LlmModel): LlmModel =
        preferred.copy(
            id = preferred.id.ifBlank { alternate.id },
            name = preferred.name.ifBlank { alternate.name },
            displayNameOverride = preferred.displayNameOverride.takeUnless { it.isNullOrBlank() }
                ?: alternate.displayNameOverride,
            providerType = preferred.providerType.ifBlank { alternate.providerType },
            providerName = preferred.providerName.takeUnless { it.isNullOrBlank() } ?: alternate.providerName,
            providerCategory = preferred.providerCategory.takeUnless { it.isNullOrBlank() }
                ?: alternate.providerCategory,
            modelEndpointType = preferred.modelEndpointType.takeUnless { it.isNullOrBlank() }
                ?: alternate.modelEndpointType,
            modelEndpoint = preferred.modelEndpoint.takeUnless { it.isNullOrBlank() } ?: alternate.modelEndpoint,
            modelWrapper = preferred.modelWrapper.takeUnless { it.isNullOrBlank() } ?: alternate.modelWrapper,
            contextWindow = preferred.contextWindow?.takeIf { it > 0 } ?: alternate.contextWindow,
            maxOutputTokens = preferred.maxOutputTokens?.takeIf { it > 0 } ?: alternate.maxOutputTokens,
            temperature = preferred.temperature ?: alternate.temperature,
            maxTokens = preferred.maxTokens?.takeIf { it > 0 } ?: alternate.maxTokens,
            enableReasoner = preferred.enableReasoner ?: alternate.enableReasoner,
            reasoningEffort = preferred.reasoningEffort.takeUnless { it.isNullOrBlank() }
                ?: alternate.reasoningEffort,
            maxReasoningTokens = preferred.maxReasoningTokens?.takeIf { it > 0 }
                ?: alternate.maxReasoningTokens,
            frequencyPenalty = preferred.frequencyPenalty ?: alternate.frequencyPenalty,
            compatibilityType = preferred.compatibilityType.takeUnless { it.isNullOrBlank() }
                ?: alternate.compatibilityType,
            verbosity = preferred.verbosity.takeUnless { it.isNullOrBlank() } ?: alternate.verbosity,
            tier = preferred.tier.takeUnless { it.isNullOrBlank() } ?: alternate.tier,
            parallelToolCalls = preferred.parallelToolCalls ?: alternate.parallelToolCalls,
        )

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
