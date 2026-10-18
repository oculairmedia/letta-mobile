package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.workspace.directory.DevicePath
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerGates

/**
 * Where the editor's link rows go, each null when this host has no such page (the row is left out).
 * [dreaming] and [secrets] open the platform-bound shared panels in a pane.
 */
data class AgentEditorLinks(
    val memory: (() -> Unit)? = null,
    val schedules: (() -> Unit)? = null,
    val channels: (() -> Unit)? = null,
    val dreaming: (() -> Unit)? = null,
    val secrets: (() -> Unit)? = null,
) {
    val isEmpty: Boolean get() = listOf(memory, schedules, channels, dreaming, secrets).all { it == null }
}

/** The agent's saved default cwd; [onChange] persists it (blank clears). */
data class AgentEditorCwdDefaults(val path: String, val onChange: (String) -> Unit)

/** The page's own panes the rows open (the mascot, model and tool pickers), and what [mascotLabel] reads. */
data class AgentEditorOpeners(
    val mascotLabel: String,
    val onMascot: () -> Unit,
    val onModel: () -> Unit,
    val onTools: () -> Unit,
    val onDeleted: () -> Unit = {},
    val links: AgentEditorLinks = AgentEditorLinks(),
)

/** The section keys (and the test handles the page tags them with). */
object AgentEditorSectionKeys {
    const val STARTER = "agent-starter"
    const val IDENTITY = "agent-identity"
    const val MODEL = "agent-model"
    const val PROMPT = "agent-prompt"
    const val TOOLS = "agent-tools"
    const val DEFAULTS = "agent-defaults"
    const val MORE = "agent-more"
    const val DANGER = "agent-danger"
}

/**
 * The agent editor as settings sections (primitive P9): identity, model, system prompt, tools, the
 * agent's other pages and the danger zone; a new agent first picks where it starts and has neither
 * links nor a danger zone. Every row is a shared SettingsRow, drawn by SettingsModelSection.
 */
class AgentEditorSections(
    private val state: AgentEditorState,
    private val actions: AgentEditorActions,
    private val openers: AgentEditorOpeners,
    private val defaultCwd: AgentEditorCwdDefaults? = null,
) {
    private val form get() = state.form

    fun build(): List<SettingsSectionModel> = listOfNotNull(
        starter(),
        identity(),
        model(),
        prompt(),
        tools(),
        defaults(),
        more(),
        danger(),
    )

    private fun starter(): SettingsSectionModel? {
        if (!state.creating) return null
        val options = listOf(SettingsOption(AgentStarter.BLANK_KEY, "Blank agent", "Start from nothing")) +
            state.starters.map { SettingsOption(it.agentId, it.name, "Copy its model, prompt and tools") }
        val row = SettingsRow.Choice(
            key = "starter",
            title = "Start from",
            options = options,
            selected = state.starter.key,
            onSelect = { key -> actions.pickStarter(state.starters.firstOrNull { it.agentId == key } ?: AgentStarter.Blank) },
        )
        return SettingsSectionModel(AgentEditorSectionKeys.STARTER, "New agent", listOf(row))
    }

    private fun identity() = SettingsSectionModel(
        key = AgentEditorSectionKeys.IDENTITY,
        title = "Identity",
        rows = listOf(
            SettingsRow.Link("mascot", "Mascot", supporting = "How the agent looks everywhere", value = openers.mascotLabel, onClick = openers.onMascot),
            SettingsRow.Text("name", "Name", form.name, problem = { if (it.isBlank()) AgentEditorValidation.NAME_REQUIRED else null }) { text -> actions.update { copy(name = text) } },
            SettingsRow.Text("description", "Description", form.description, placeholder = "What this agent is for") { text ->
                actions.update { copy(description = text) }
            },
        ),
    )

    private fun model(): SettingsSectionModel = SettingsSectionModel(
        key = AgentEditorSectionKeys.MODEL,
        title = "Model",
        rows = listOfNotNull(
            SettingsRow.Link(
                key = "model",
                title = "Model",
                supporting = state.problems[AgentEditorField.Model] ?: if (state.creating && form.model.isBlank()) "The server's default" else null,
                value = state.modelOption?.label ?: form.model.ifBlank { "Default" },
                onClick = openers.onModel,
            ),
            effort(),
            SettingsRow.Text(
                key = "context-limit",
                title = "Context limit",
                value = form.contextLimit,
                placeholder = "The model's own window",
                supporting = "Tokens the agent keeps in context before it compacts",
                numeric = true,
                problem = AgentEditorValidation::contextLimitProblem,
            ) { text -> actions.update { copy(contextLimit = text) } },
        ),
    )

    private fun effort(): SettingsRow? {
        val efforts = state.efforts.takeIf { it.isNotEmpty() } ?: return null
        val options = listOf(SettingsOption(DEFAULT_EFFORT, "Default")) + efforts.map { SettingsOption(it, it.replaceFirstChar(Char::uppercaseChar)) }
        return SettingsRow.Choice(
            key = "effort",
            title = "Reasoning effort",
            options = options,
            selected = form.reasoningEffort ?: DEFAULT_EFFORT,
        ) { key -> actions.update { copy(reasoningEffort = key.takeIf { it != DEFAULT_EFFORT }) } }
    }

    private fun prompt() = SettingsSectionModel(
        key = AgentEditorSectionKeys.PROMPT,
        title = "System prompt",
        rows = listOf(
            compact(),
            SettingsRow.Text("system", "Instructions", form.systemPrompt, placeholder = promptPlaceholder(), lines = PROMPT_LINES) { text ->
                actions.update { copy(systemPrompt = text) }
            },
        ),
    )

    /** The explicit compact-profile choice: on by default for a small model, never applied silently. */
    private fun compact() = SettingsRow.Toggle(
        key = COMPACT_KEY,
        title = "Compact prompt for small models",
        checked = state.compactProfile,
        supporting = (if (state.compactRecommended) "Recommended for this model. " else "") + COMPACT_SUPPORTING,
    ) { on -> actions.update { copy(compactProfile = on) } }

    private fun promptPlaceholder(): String =
        if (state.compactProfile) "Leave blank for the built-in short prompt" else "Who the agent is and how it works"

    private fun tools(): SettingsSectionModel {
        val known = state.tools.associateBy { it.id } + state.agent?.tools.orEmpty().associate { it.id.value to AgentToolOption(it.id.value, it.name, it.description) }
        val attached = form.toolIds.map { known[it] ?: AgentToolOption(it, it) }.sortedBy { it.name.lowercase() }
        val rows = attached.map { tool ->
            SettingsRow.Link("tool-${tool.id}", tool.name, supporting = if (tool.mcp) "MCP" else tool.description, onClick = openers.onTools)
        }.ifEmpty { listOf(SettingsRow.Note("no-tools", SettingsStatus("No tools attached yet."))) }
        val manage = SettingsRow.Link("manage-tools", "Add or remove tools", value = attached.size.toString(), onClick = openers.onTools)
        val supporting = if (state.compactProfile) "A compact agent gets a few core tools" else "Built-in tools and tools from MCP servers"
        return SettingsSectionModel(AgentEditorSectionKeys.TOOLS, "Tools", rows + manage, supporting = supporting)
    }

    private fun defaults(): SettingsSectionModel? {
        val cwd = defaultCwd ?: return null
        val row = SettingsRow.Text(
            key = DEFAULT_CWD_KEY,
            title = "Default working directory",
            value = cwd.path,
            placeholder = "New chats start here",
            supporting = "New conversations for this agent start in this folder",
            problem = ::cwdProblem,
            onChange = cwd.onChange,
        )
        return SettingsSectionModel(AgentEditorSectionKeys.DEFAULTS, "Defaults", listOf(row))
    }

    private fun more(): SettingsSectionModel? {
        val links = openers.links.takeIf { !state.creating && !it.isEmpty } ?: return null
        val rows = listOfNotNull(
            links.memory?.let { SettingsRow.Link("memory", "Memory", supporting = "Blocks and files", onClick = it) },
            links.schedules?.let { SettingsRow.Link("schedules", "Schedules", supporting = "Runs on a timer", onClick = it) },
            links.channels?.let { SettingsRow.Link("channels", "Channels", supporting = "Where it talks besides here", onClick = it) },
            links.dreaming?.let { SettingsRow.Link("dreaming", "Dreaming", supporting = "Reflection between conversations", onClick = it) },
            links.secrets?.let { SettingsRow.Link("secrets", "Secrets", supporting = "Keys its tools can use", onClick = it) },
        )
        return SettingsSectionModel(AgentEditorSectionKeys.MORE, "More", rows)
    }

    private fun danger(): SettingsSectionModel? {
        if (state.creating) return null
        val name = form.name.ifBlank { "this agent" }
        val delete = SettingsRow.Link(
            key = "delete",
            title = "Delete agent",
            supporting = "Removes the agent, its memory and its conversations",
            destructive = true,
            enabled = !state.saving,
            confirm = SettingsConfirmText(
                title = "Delete $name?",
                message = "This removes the agent with its memory and conversations. It cannot be undone.",
                confirmLabel = "Delete",
            ),
        ) { actions.delete(openers.onDeleted) }
        return SettingsSectionModel(AgentEditorSectionKeys.DANGER, "Danger zone", listOf(delete))
    }

    private companion object {
        const val DEFAULT_EFFORT = "default"
        const val PROMPT_LINES = 6
        const val COMPACT_KEY = "compact-profile"
        const val DEFAULT_CWD_KEY = "default-cwd"
        const val COMPACT_SUPPORTING = "Short instructions, a few core tools and no skills list, so a small model stays fast and on topic."

        fun cwdProblem(path: String): String? {
            if (path.isBlank()) return null
            return WorkingDirectoryPickerGates.NOT_ABSOLUTE.takeUnless { DevicePath(path).isAbsolute }
        }
    }
}

/** The error line the page shows above its sections, if any. */
fun AgentEditorState.errorNote(): SettingsRow.Note? = error?.let { SettingsRow.Note("error", SettingsStatus(it, SettingsTone.Error)) }
