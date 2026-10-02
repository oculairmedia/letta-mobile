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

    private fun provider(id: String, name: String, type: String) = ConnectableProvider(
        id = id,
        displayName = name,
        description = "",
        providerType = type,
        providerName = id,
        isOauth = false,
        requiresApiKey = false,
        authMethods = emptyList(),
        connections = emptyList(),
    )

    private fun ConnectableProvider.subscription(alias: String) = copy(isOauth = true, providerName = alias)

    private fun ConnectableProvider.taking(vararg fields: ProviderField) = copy(
        requiresApiKey = fields.any { it.required && it.secret },
        authMethods = listOf(ProviderAuthMethod(id = null, label = "API key", fields = fields.toList())),
    )

    private fun ConnectableProvider.connectedAs(connection: ProviderConnection) = copy(connections = listOf(connection))

    val providers: List<ConnectableProvider> = listOf(
        provider("openai-codex-oauth", "OpenAI Codex", "chatgpt_oauth")
            .subscription("chatgpt-plus-pro")
            .connectedAs(ProviderConnection("chatgpt-plus-pro", "chatgpt_oauth", "oauth", null)),
        provider("lmstudio", "LM Studio (local)", "lmstudio_openai")
            .taking(baseUrl.copy(required = false), optionalKey)
            .connectedAs(ProviderConnection("lc-lmstudio", "lmstudio", "api", "https://lmstudio.lan.example/v1")),
        provider("anthropic-oauth", "Anthropic (Claude Pro/Max)", "anthropic").subscription("anthropic"),
        provider("anthropic", "Anthropic", "anthropic").taking(apiKey).copy(description = "Connect a Anthropic API key"),
        provider("minimax", "MiniMax", "minimax")
            .taking(apiKey)
            .connectedAs(ProviderConnection("minimax", "minimax", "api", null)),
        provider("openai-compatible", "OpenAI-compatible API", "openai")
            .taking(optionalKey, baseUrl)
            .copy(description = "Connect an OpenAI-compatible Chat Completions endpoint"),
    )

    private fun model(handle: String, label: String, effort: String? = null) = CatalogModel(
        model = LlmModel(id = handle, name = label, handle = handle, displayNameOverride = label, providerType = handle.substringBefore('/')),
        exposed = true,
        reasoningEfforts = emptyList(),
        reasoningEffort = effort,
    )

    private fun CatalogModel.hidden() = copy(exposed = false)

    val models: List<CatalogModel> = listOf(
        model("chatgpt-plus-pro/gpt-5.5", "GPT-5.5", effort = "medium"),
        model("chatgpt-plus-pro/gpt-5.5-codex", "GPT-5.5 Codex", effort = "high"),
        model("chatgpt-plus-pro/gpt-5.5-mini", "GPT-5.5 Mini").hidden(),
        model("minimax/minimax-m3", "MiniMax M3", effort = "medium"),
        model("minimax/minimax-m3-lightning", "MiniMax M3 Lightning"),
        model("lmstudio/qwen3-coder-30b", "qwen3-coder-30b"),
        model("lmstudio/gemma-4-27b", "gemma-4-27b").hidden(),
        model("lmstudio/gpt-oss-120b", "gpt-oss-120b", effort = "low"),
    )

    const val SELECTED = "chatgpt-plus-pro/gpt-5.5"

    /** The picker over the fixture host with [SELECTED] current; tests `copy` the rest. */
    fun pickerState() = ModelPickerState(
        groups = ModelPickerCatalog.groups(providers, models, SELECTED),
        canEditModels = true,
    )

    fun managementState() = ProviderManagementState(sections = ProviderCatalogComposer.compose(providers, models))
}
