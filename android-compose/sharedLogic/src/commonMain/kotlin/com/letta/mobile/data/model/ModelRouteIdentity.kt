package com.letta.mobile.data.model

data class ModelRouteIdentity(
    val providerType: String? = null,
    val providerName: String? = null,
    val providerCategory: String? = null,
    val modelEndpoint: String? = null,
) {
    val isSpecified: Boolean
        get() = listOf(providerType, providerName, providerCategory, modelEndpoint)
            .any { !it.isNullOrBlank() }

    fun matches(model: LlmModel): Boolean =
        fieldMatches(model.providerType, providerType) &&
            fieldMatches(model.providerName, providerName) &&
            fieldMatches(model.providerCategory, providerCategory) &&
            fieldMatches(model.modelEndpoint, modelEndpoint, ignoreCase = false)

    private fun fieldMatches(
        actual: String?,
        expected: String?,
        ignoreCase: Boolean = true,
    ): Boolean = expected.isNullOrBlank() || actual?.equals(expected, ignoreCase = ignoreCase) == true

    companion object {
        fun from(agent: Agent): ModelRouteIdentity = ModelRouteIdentity(
            providerType = agent.modelSettings?.providerType
                ?: agent.llmConfig?.modelEndpointType,
            providerName = agent.modelSettings?.providerName
                ?: agent.llmConfig?.providerName,
            providerCategory = agent.modelSettings?.providerCategory
                ?: agent.llmConfig?.providerCategory,
            modelEndpoint = agent.llmConfig?.modelEndpoint,
        )
    }
}
