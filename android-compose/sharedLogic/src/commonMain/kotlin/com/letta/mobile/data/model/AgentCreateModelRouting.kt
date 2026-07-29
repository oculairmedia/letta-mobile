package com.letta.mobile.data.model

/**
 * Builds the route-bearing LLM config for an agent created from a catalog row.
 * Catalog-only context enrichment is carried explicitly, while custom routes
 * retain the provenance needed to avoid applying LLMux defaults.
 */
fun LlmModel.toAgentCreateLlmConfig(): LlmConfig? {
    val context = contextWindow?.takeIf { it > 0 }
    val hasRouteMetadata = listOf(
        providerName,
        providerCategory,
        modelEndpointType,
        modelEndpoint,
        modelWrapper,
    ).any { !it.isNullOrBlank() }
    if (!hasRouteMetadata && context == null && model.isNullOrBlank()) return null

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
    val selectedProviderType = selectedModel.providerType.takeIf { it.isNotBlank() }
    val hasProviderMetadata = listOf(
        selectedProviderType,
        selectedModel.providerName,
        selectedModel.providerCategory,
    ).any { !it.isNullOrBlank() }
    val routedSettings = if (modelSettings != null || hasProviderMetadata) {
        (modelSettings ?: ModelSettings()).copy(
            providerType = modelSettings?.providerType ?: selectedProviderType,
            providerName = modelSettings?.providerName ?: selectedModel.providerName,
            providerCategory = modelSettings?.providerCategory ?: selectedModel.providerCategory,
        )
    } else {
        null
    }
    return copy(
        modelSettings = routedSettings,
        llmConfig = llmConfig ?: selectedModel.toAgentCreateLlmConfig(),
    )
}

private fun String.removeRoutingProviderPrefix(): String {
    val slash = indexOf('/')
    return if (slash > 0 && slash < lastIndex) substring(slash + 1) else this
}
