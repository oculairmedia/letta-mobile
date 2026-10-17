package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.CompactAgentProfile
import com.letta.mobile.ui.chat.session.ChatModelOption
import kotlinx.serialization.json.JsonElement

/*
 * letta-mobile-ckmub.3.24 (hard cut S15): the one agent editor of every client. The page is the
 * sharedUI AgentEditorPage (edit an agent, or create one); this is its platform-neutral model.
 */

/** Which agent the editor is on: a new one, or an existing one by id. */
sealed interface AgentEditorMode {
    data object Create : AgentEditorMode

    data class Edit(val agentId: String) : AgentEditorMode

    companion object {
        /** The editor for [agentId], or the new-agent flow when there is none. */
        fun of(agentId: String?): AgentEditorMode = agentId?.takeIf { it.isNotBlank() }?.let(::Edit) ?: Create
    }
}

/** Where a new agent starts: blank, or a copy of an existing agent's model, prompt, tools and tags. */
sealed interface AgentStarter {
    val key: String

    data object Blank : AgentStarter {
        override val key: String get() = BLANK_KEY
    }

    data class CopyOf(val agentId: String, val name: String) : AgentStarter {
        override val key: String get() = agentId
    }

    companion object {
        const val BLANK_KEY = "blank"
    }
}

/**
 * What the form holds. Text fields keep what was typed ([contextLimit] too, so a half-typed number
 * is shown and reported rather than dropped); [metadata] is what a new agent is created with (its
 * mascot identity).
 */
data class AgentEditorForm(
    val name: String = "",
    val description: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
    val contextLimit: String = "",
    val systemPrompt: String = "",
    val toolIds: Set<String> = emptySet(),
    val metadata: Map<String, JsonElement> = emptyMap(),
    /** The compact profile ([CompactAgentProfile]) as the user set it; null follows the model's recommendation. */
    val compactProfile: Boolean? = null,
)

/** A field the validation can fault. */
enum class AgentEditorField { Name, Model, ContextLimit }

/** A tool the agent can have; [mcp] tools come from an MCP server and are listed apart. */
data class AgentToolOption(val id: String, val name: String, val description: String? = null, val mcp: Boolean = false)

/** What a create returned: the new agent and, where the backend opened one, its first conversation. */
data class AgentCreated(val agentId: String, val conversationId: String? = null)

/** Everything the editor page draws. */
data class AgentEditorState(
    val mode: AgentEditorMode,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Why the agent could not be loaded; the page offers a retry. */
    val loadError: String? = null,
    /** Why the last save or delete failed. */
    val error: String? = null,
    val form: AgentEditorForm = AgentEditorForm(),
    /** The form as loaded (edit): what a save compares against, so only changes are written. */
    val original: AgentEditorForm? = null,
    val agent: Agent? = null,
    val starter: AgentStarter = AgentStarter.Blank,
    val starters: List<AgentStarter.CopyOf> = emptyList(),
    val models: List<ChatModelOption> = emptyList(),
    /** Why the models could not be listed (letta-mobile-by4vm.121.11); null when they were, or still may be. */
    val modelsUnavailable: String? = null,
    val tools: List<AgentToolOption> = emptyList(),
    /** The listed models (by selection value) small enough that a new agent on them starts compact. */
    val compactModels: Set<String> = emptySet(),
) {
    val agentId: String? get() = (mode as? AgentEditorMode.Edit)?.agentId
    val creating: Boolean get() = mode == AgentEditorMode.Create
    val problems: Map<AgentEditorField, String> get() = AgentEditorValidation.problems(form, mode)
    val dirty: Boolean get() = creating || form != original
    val canSave: Boolean get() = !loading && !saving && loadError == null && dirty && problems.isEmpty()

    /**
     * Whether the selected model calls for the compact profile: a small listed model, or a small-model
     * name; with no model picked, the server's default when the catalog lists only that one model.
     */
    val compactRecommended: Boolean
        get() = if (form.model.isBlank()) {
            models.singleOrNull()?.handle?.let { it in compactModels } == true
        } else {
            form.model in compactModels || CompactAgentProfile.isSmallModelName(form.model)
        }

    /** Whether the agent gets (or has) the compact profile: the user's choice, else the recommendation. */
    val compactProfile: Boolean get() = form.compactProfile ?: compactRecommended

    /** The selected model as the picker lists it, if it is listed. */
    val modelOption: ChatModelOption? get() = models.firstOrNull { it.handle == form.model }

    /** The efforts the selected model accepts; the current one stays offered even when the list does not name it. */
    val efforts: List<String>
        get() = (modelOption?.reasoningEfforts.orEmpty() + listOfNotNull(form.reasoningEffort)).distinct()
}

/** What the page asks of the editor. The controller implements it; tests record it. */
interface AgentEditorActions {
    fun update(change: AgentEditorForm.() -> AgentEditorForm)
    fun pickStarter(starter: AgentStarter)
    fun save(onSaved: (AgentCreated) -> Unit)
    fun delete(onDeleted: () -> Unit)
    fun retry()
    fun dismissError()

    /**
     * Writes [metadata] onto the agent being edited at once, outside the form's Save (the mascot
     * identity: a pick should not wait on, or be lost to, a validation error elsewhere on the form).
     */
    suspend fun writeMetadata(metadata: Map<String, JsonElement>)
}
