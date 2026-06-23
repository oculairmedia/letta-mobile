package com.letta.mobile.data.model

/** Provenance badge shown next to a model in the picker. */
enum class ModelBadge { Byok, Local }

/** A single selectable model, with display metadata derived for the picker. */
data class ModelOption(
    /** The value passed back on selection (matches the agent `model` handle). */
    val value: String,
    val displayName: String,
    /** e.g. "200K · reasoning"; null when nothing notable is known. */
    val sublabel: String?,
    val badge: ModelBadge?,
)

/** Models for one provider, in display order. */
data class ModelGroup(val provider: String, val models: List<ModelOption>)

/**
 * Shared grouping + metadata logic for the model picker (Penpot "Model Picker ·
 * sheet"): provider sections (Anthropic / OpenAI / Local · On-device), a
 * capability sublabel (context window + reasoning), and BYOK/LOCAL badges — all
 * derived from [LlmModel]'s server fields.
 *
 * Lives in commonMain so the desktop sheet and the mobile model picker share one
 * source of truth.
 */
object ModelCatalog {
    /** The selection value for [model], matching how agents store `model`. */
    fun valueOf(model: LlmModel): String =
        model.handle?.takeIf { it.isNotBlank() } ?: model.name.ifBlank { model.id }

    /** Group [models] by provider, preserving a stable provider order. */
    fun group(models: List<LlmModel>): List<ModelGroup> {
        val ordered = LinkedHashMap<String, MutableList<ModelOption>>()
        models.forEach { model ->
            val provider = providerLabel(model)
            ordered.getOrPut(provider) { mutableListOf() }.add(toOption(model))
        }
        return ordered.entries
            .sortedBy { providerSortKey(it.key) }
            .map { ModelGroup(it.key, it.value) }
    }

    /** Case-insensitive match of [query] against a model's name/value/provider. */
    fun matches(option: ModelOption, provider: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return option.displayName.contains(q, ignoreCase = true) ||
            option.value.contains(q, ignoreCase = true) ||
            provider.contains(q, ignoreCase = true)
    }

    /** Filter grouped models by [query], dropping now-empty groups. */
    fun filter(groups: List<ModelGroup>, query: String): List<ModelGroup> {
        if (query.isBlank()) return groups
        return groups.mapNotNull { group ->
            val kept = group.models.filter { matches(it, group.provider, query) }
            if (kept.isEmpty()) null else group.copy(models = kept)
        }
    }

    private fun toOption(model: LlmModel): ModelOption = ModelOption(
        value = valueOf(model),
        displayName = model.displayName,
        sublabel = sublabel(model),
        badge = badge(model),
    )

    private fun sublabel(model: LlmModel): String? {
        val parts = buildList {
            model.contextWindow?.takeIf { it > 0 }?.let { add(formatContext(it)) }
            if (model.enableReasoner == true || !model.reasoningEffort.isNullOrBlank()) add("reasoning")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun formatContext(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

    private fun badge(model: LlmModel): ModelBadge? = when {
        isLocal(model) -> ModelBadge.Local
        model.providerCategory.equals("byok", ignoreCase = true) -> ModelBadge.Byok
        else -> null
    }

    private fun isLocal(model: LlmModel): Boolean {
        val haystack = listOfNotNull(
            model.providerType,
            model.providerName,
            model.providerCategory,
            model.modelEndpointType,
        ).joinToString(" ").lowercase()
        return LOCAL_HINTS.any { it in haystack }
    }

    private fun providerLabel(model: LlmModel): String {
        if (isLocal(model)) return "Local · On-device"
        val raw = model.providerName?.takeIf { it.isNotBlank() }
            ?: model.providerType.takeIf { it.isNotBlank() }
            ?: "Other"
        return KNOWN_PROVIDERS[raw.lowercase()] ?: raw.replaceFirstChar { it.uppercase() }
    }

    private fun providerSortKey(provider: String): String = when (provider) {
        "Local · On-device" -> "zzz_local"
        "Other" -> "zzy_other"
        else -> provider.lowercase()
    }

    private val KNOWN_PROVIDERS = mapOf(
        "anthropic" to "Anthropic",
        "openai" to "OpenAI",
        "google" to "Google",
        "google_ai" to "Google",
        "groq" to "Groq",
        "together" to "Together",
        "deepseek" to "DeepSeek",
        "mistral" to "Mistral",
        "xai" to "xAI",
    )

    private val LOCAL_HINTS = listOf("local", "ollama", "lmstudio", "lm_studio", "llamacpp", "llama_cpp", "on-device", "ondevice")
}
