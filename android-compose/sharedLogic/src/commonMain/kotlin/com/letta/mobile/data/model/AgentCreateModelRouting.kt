package com.letta.mobile.data.model

/**
 * Builds the route-bearing LLM config for an agent created from a catalog row.
 * Catalog-only context enrichment is carried explicitly, while custom routes
 * retain the provenance needed to avoid applying LLMux defaults.
 */
fun LlmModel.toAgentCreateLlmConfig(): LlmConfig? {
    val context = contextWindow?.takeIf { it > 0 }
    if (!hasAgentCreateConfigMetadata(context)) return null

    val selection = ModelCatalog.valueOf(this)
    return LlmConfig(
        model = model?.takeIf { it.isNotBlank() }
            ?: selection.removeRoutingProviderPrefix().ifBlank { null },
        displayName = displayNameOverride,
        modelEndpointType = modelEndpointType,
        modelEndpoint = modelEndpoint,
        providerName = providerName,
        providerCategory = providerCategory,
        modelWrapper = modelWrapper,
        contextWindow = context,
        handle = selection,
    )
}

/**
 * Adds catalog-derived routing fields to an agent-create request without
 * replacing settings the caller supplied explicitly.
 */
fun AgentCreateParams.withCatalogModelRouting(availableModels: List<LlmModel>): AgentCreateParams {
    val selectedModel = ModelCatalog.selectedModel(availableModels, model) ?: return this
    return copy(
        modelSettings = modelSettings.withRoutingFrom(selectedModel),
        llmConfig = llmConfig ?: selectedModel.toAgentCreateLlmConfig(),
    )
}

private fun LlmModel.hasAgentCreateConfigMetadata(context: Int?): Boolean =
    listOf(
        model,
        providerName,
        providerCategory,
        modelEndpointType,
        modelEndpoint,
        modelWrapper,
        context?.toString(),
    ).any { !it.isNullOrBlank() }

private fun ModelSettings?.withRoutingFrom(model: LlmModel): ModelSettings? {
    val catalogRouting = ModelSettings(
        providerType = model.providerType.ifBlank { null },
        providerName = model.providerName,
        providerCategory = model.providerCategory,
    )
    return this?.copy(
        providerType = providerType ?: catalogRouting.providerType,
        providerName = providerName ?: catalogRouting.providerName,
        providerCategory = providerCategory ?: catalogRouting.providerCategory,
    ) ?: catalogRouting.takeIf(ModelSettings::hasProviderRouting)
}

private fun ModelSettings.hasProviderRouting(): Boolean =
    listOf(providerType, providerName, providerCategory).any { !it.isNullOrBlank() }

private fun String.removeRoutingProviderPrefix(): String {
    val slash = indexOf('/')
    return if (slash > 0 && slash < lastIndex) substring(slash + 1) else this
}
