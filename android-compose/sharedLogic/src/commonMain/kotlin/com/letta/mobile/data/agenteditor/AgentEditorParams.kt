package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.CompactAgentProfile
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.withCatalogModelRouting
import kotlinx.collections.immutable.toImmutableList

/**
 * The provider types a `model_settings` write accepts (moved from feature-editagent's use cases):
 * a write naming any other provider type is refused by the server.
 */
object ModelSettingsProviderTypes {
    private val Supported = setOf(
        "openai", "sglang", "anthropic", "google_ai", "google_vertex", "azure", "xai", "zai", "groq",
        "deepseek", "together", "bedrock", "baseten", "openrouter", "chatgpt_oauth",
    )

    /** [providerType] when supported, else the provider prefix of [modelHandle] when that is; null when neither is. */
    fun normalize(providerType: String?, modelHandle: String?): String? {
        val explicit = providerType?.trim()?.lowercase().orEmpty()
        if (explicit in Supported) return explicit
        val prefix = modelHandle?.substringBefore('/', missingDelimiterValue = "")?.trim()?.lowercase().orEmpty()
        return prefix.takeIf { it in Supported }
    }
}

/** How a form is filled from an agent, and what a save sends. */
object AgentEditorParams {
    /** The form for [agent] as it is now. */
    fun formOf(agent: Agent): AgentEditorForm = AgentEditorForm(
        name = agent.name,
        description = agent.description.orEmpty(),
        model = agent.model.orEmpty(),
        reasoningEffort = agent.modelSettings?.reasoningEffort ?: agent.llmConfig?.reasoningEffort,
        contextLimit = agent.contextWindowLimit?.toString().orEmpty(),
        systemPrompt = agent.system.orEmpty(),
        toolIds = agent.tools.map { it.id.value }.toSet(),
        compactProfile = CompactAgentProfile.isCompact(agent.tags),
    )

    /** A new agent's form copied from [source]: everything but its name and identity. */
    fun starterFormOf(source: Agent, current: AgentEditorForm): AgentEditorForm = formOf(source).copy(
        name = current.name,
        metadata = current.metadata,
        // A compact source stays compact; otherwise the choice still follows the model.
        compactProfile = true.takeIf { CompactAgentProfile.isCompact(source.tags) } ?: current.compactProfile,
    )

    /**
     * The agent PATCH for what changed between [original] and [form]; null when only the tools
     * changed (they are attached and detached on their own). [agent] supplies the settings an
     * effort change must carry.
     */
    fun updateOf(original: AgentEditorForm, form: AgentEditorForm, agent: Agent?): AgentUpdateParams? {
        val compactChanged = form.compactProfile != null && form.compactProfile != original.compactProfile
        // Turning the compact profile on gives a blank prompt the short one, as a new compact agent gets.
        val turningOn = compactChanged && form.compactProfile == true
        val prompt = form.systemPrompt.ifBlank { if (turningOn) CompactAgentProfile.SYSTEM_PROMPT else "" }
        val params = AgentUpdateParams(
            name = form.name.trim().takeIf { it != original.name },
            description = form.description.takeIf { it != original.description },
            model = form.model.takeIf { it != original.model },
            system = prompt.takeIf { it != original.systemPrompt },
            tags = CompactAgentProfile.tagsWith(agent?.tags, form.compactProfile == true).toImmutableList().takeIf { compactChanged },
            contextWindowLimit = AgentEditorValidation.contextLimitOf(form.contextLimit).takeIf { form.contextLimit != original.contextLimit },
            modelSettings = effortSettings(form, agent).takeIf { form.reasoningEffort != original.reasoningEffort },
        )
        return params.takeIf { it != AgentUpdateParams() }
    }

    /**
     * The create request for [form], starting from [starter] (an agent whose tags it keeps), routed over
     * [models]; with [AgentEditorForm.compactProfile] on, as a compact agent ([CompactAgentProfile]).
     */
    fun createOf(form: AgentEditorForm, starter: Agent?, models: List<LlmModel>): AgentCreateParams {
        val params = AgentCreateParams(
            name = form.name.trim(),
            description = form.description.trim().ifBlank { null },
            model = form.model.ifBlank { null },
            system = form.systemPrompt.ifBlank { null },
            toolIds = form.toolIds.map(::ToolId).ifEmpty { null }?.toImmutableList(),
            tags = starter?.tags?.let { CompactAgentProfile.tagsWith(it, compact = false) }?.ifEmpty { null }?.toImmutableList(),
            metadata = form.metadata.ifEmpty { null },
            includeBaseTools = true,
            contextWindowLimit = AgentEditorValidation.contextLimitOf(form.contextLimit),
            modelSettings = form.reasoningEffort?.let { effortSettings(form, starter) },
        ).withCatalogModelRouting(models)
        return if (form.compactProfile == true) CompactAgentProfile.applyTo(params) else params
    }

    /** The tools to attach and to detach to go from [original] to [form]. */
    fun toolChanges(original: AgentEditorForm, form: AgentEditorForm): Pair<Set<String>, Set<String>> =
        (form.toolIds - original.toolIds) to (original.toolIds - form.toolIds)

    private fun effortSettings(form: AgentEditorForm, agent: Agent?): ModelSettings {
        val existing = agent?.modelSettings ?: ModelSettings()
        val providerType = ModelSettingsProviderTypes.normalize(existing.providerType ?: agent?.llmConfig?.modelEndpointType, form.model)
        return existing.copy(providerType = providerType, reasoningEffort = form.reasoningEffort)
    }
}
