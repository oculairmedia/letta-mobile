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
    if (!hasRouteMetadata && context == null) return null

    val selection = ModelCatalog.valueOf(this)
    return LlmConfig(
        model = ModelCatalogNormalizer.underlyingModelId(selection).ifBlank { null },
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
