package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ModelPickerCatalog
import com.letta.mobile.data.repository.modelcontrol.ModelPickerState
import com.letta.mobile.data.repository.modelcontrol.ProviderAuthMethod
import com.letta.mobile.data.repository.modelcontrol.ProviderCatalogComposer
import com.letta.mobile.data.repository.modelcontrol.ProviderConnection
import com.letta.mobile.data.repository.modelcontrol.ProviderField
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState

/**
 * A host shaped like the live one (letta-code 0.32.x): a ChatGPT subscription
 * and an LM Studio endpoint connected, an Anthropic subscription and key not
 * yet, an OpenAI-compatible endpoint to add; models with and without tiers,
 * one hidden.
 */
internal object ModelControlUiFixtures {
    private val apiKey = ProviderField(key = "apiKey", label = "API Key", secret = true, required = true)
    private val optionalKey = apiKey.copy(required = false)
    private val baseUrl = ProviderField(key = "baseUrl", label = "Base URL", placeholder = "http://127.0.0.1:1234/v1", required = true)

    private fun provider(
        id: String,
        name: String,
        type: String,
        description: String = "",
        oauth: Boolean = false,
        fields: List<ProviderField> = emptyList(),
        connection: ProviderConnection? = null,
        providerName: String = id,
    ) = ConnectableProvider(
        id = id,
        displayName = name,
        description = description,
        providerType = type,
        providerName = providerName,
        isOauth = oauth,
        requiresApiKey = fields.any { it.required && it.secret },
        authMethods = if (fields.isEmpty()) emptyList() else listOf(ProviderAuthMethod(id = null, label = "API key", fields = fields)),
        connections = listOfNotNull(connection),
    )

    val providers: List<ConnectableProvider> = listOf(
        provider(
            id = "openai-codex-oauth", name = "OpenAI Codex", type = "chatgpt_oauth", oauth = true, providerName = "chatgpt-plus-pro",
            description = "Connect a subscription account",
            connection = ProviderConnection("chatgpt-plus-pro", "chatgpt_oauth", "oauth", null),
        ),
        provider(
            id = "lmstudio", name = "LM Studio (local)", type = "lmstudio_openai", fields = listOf(baseUrl.copy(required = false), optionalKey),
            description = "Connect LM Studio at http://127.0.0.1:1234/v1 or a remote URL",
            connection = ProviderConnection("lc-lmstudio", "lmstudio", "api", "http://192.168.50.90:8082/v1"),
        ),
        provider(id = "anthropic-oauth", name = "Anthropic (Claude Pro/Max)", type = "anthropic", oauth = true, providerName = "anthropic"),
        provider(id = "anthropic", name = "Anthropic", type = "anthropic", description = "Connect a Anthropic API key", fields = listOf(apiKey)),
        provider(
            id = "minimax", name = "MiniMax", type = "minimax", description = "Connect a MiniMax API key", fields = listOf(apiKey),
            connection = ProviderConnection("minimax", "minimax", "api", null),
        ),
        provider(
            id = "openai-compatible", name = "OpenAI-compatible API", type = "openai",
            description = "Connect an OpenAI-compatible Chat Completions endpoint", fields = listOf(optionalKey, baseUrl),
        ),
    )

    private fun model(handle: String, label: String, exposed: Boolean = true, effort: String? = null, efforts: List<String> = emptyList()) =
        CatalogModel(
            model = LlmModel(id = handle, name = label, handle = handle, displayNameOverride = label, providerType = handle.substringBefore('/')),
            exposed = exposed,
            reasoningEfforts = efforts,
            reasoningEffort = effort,
        )

    val models: List<CatalogModel> = listOf(
        model("chatgpt-plus-pro/gpt-5.5", "GPT-5.5", effort = "medium"),
        model("chatgpt-plus-pro/gpt-5.5-codex", "GPT-5.5 Codex", effort = "high"),
        model("chatgpt-plus-pro/gpt-5.5-mini", "GPT-5.5 Mini", exposed = false),
        model("minimax/minimax-m3", "MiniMax M3", effort = "medium"),
        model("minimax/minimax-m3-lightning", "MiniMax M3 Lightning"),
        model("lmstudio/qwen3-coder-30b", "qwen3-coder-30b"),
        model("lmstudio/gemma-4-27b", "gemma-4-27b", exposed = false),
        model("lmstudio/gpt-oss-120b", "gpt-oss-120b", effort = "low"),
    )

    const val SELECTED = "chatgpt-plus-pro/gpt-5.5"

    fun pickerState(
        selected: String? = SELECTED,
        refreshing: Boolean = false,
        error: String? = null,
        canEditModels: Boolean = true,
        collapsed: Set<String> = emptySet(),
        query: String = "",
    ) = ModelPickerState(
        groups = ModelPickerCatalog.groups(providers, models, selected),
        refreshing = refreshing,
        error = error,
        canEditModels = canEditModels,
        collapsed = collapsed,
        query = query,
    )

    fun managementState(query: String = "") = ProviderManagementState(
        sections = ProviderCatalogComposer.compose(providers, models),
        query = query,
    )
}
