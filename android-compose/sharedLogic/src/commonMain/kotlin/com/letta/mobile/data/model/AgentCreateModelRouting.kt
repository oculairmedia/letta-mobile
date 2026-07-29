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
fun AgentCreateParams.withCatalogModelRouting(availableModels: List<LlmModel>): AgentCreateParams =
    ModelCatalog.selectedModel(availableModels, model).applyRoutingTo(this)

private fun LlmModel?.applyRoutingTo(params: AgentCreateParams): AgentCreateParams {
    val selectedModel = this ?: return params
    return params.copy(
        modelSettings = params.modelSettings.withRoutingFrom(selectedModel),
        llmConfig = params.llmConfig ?: selectedModel.toAgentCreateLlmConfig(),
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
        maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 }
            ?: model.maxTokens?.takeIf { it > 0 },
    )
    return this?.copy(
        providerType = providerType ?: catalogRouting.providerType,
        providerName = providerName ?: catalogRouting.providerName,
        providerCategory = providerCategory ?: catalogRouting.providerCategory,
        maxOutputTokens = maxOutputTokens ?: catalogRouting.maxOutputTokens,
    ) ?: catalogRouting.takeIf(ModelSettings::hasCatalogSettings)
}

private fun ModelSettings.hasCatalogSettings(): Boolean =
    listOf(
        providerType,
        providerName,
        providerCategory,
        maxOutputTokens?.toString(),
    ).any { !it.isNullOrBlank() }

private fun String.removeRoutingProviderPrefix(): String {
    val slash = indexOf('/')
    return if (slash > 0 && slash < lastIndex) substring(slash + 1) else this
}
