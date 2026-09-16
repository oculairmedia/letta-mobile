package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.groupSubagentConversations
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.desktop.chat.ChatDetailPaneActions
import kotlinx.coroutines.launch

/** Model picker options: display label to route-stable selection token. */
internal fun buildModelOptions(availableModels: List<LlmModel>): List<Pair<String, String>> =
    availableModels.map { model ->
        val label = model.displayNameOverride?.takeIf { it.isNotBlank() } ?: model.name
        val value = ModelCatalog.selectionValue(availableModels, model)
        label to value
    }

/**
 * Distinct agents (by id, since many agents share a display name) that have
 * conversations — the rail orbs — ordered most-recent-first so each stack's
 * "first" member (the click/open fallback) is the one with the most recent
 * conversation. Roster-only agents (no recent or any conversations, e.g.
 * bulk-imported fleets hidden by DEFAULT_CONVERSATION_LIMIT) follow
 * alphabetically.
 */
internal fun buildRailAgents(
    conversations: List<DesktopConversationSummary>,
    rosterAgents: List<Agent>,
): List<Pair<String, String>> {
    // A conversation's agentName can be a raw agent id when name resolution
    // missed at conversation-load time; the roster often has the real name by
    // now (refreshAgentsIfStale), so prefer whichever source has an actual
    // name instead of parroting the id.
    val rosterNameById = rosterAgents
        .filter { it.name.isNotBlank() }
        .associate { it.id.value to it.name }
    val fromConversations = conversations
        .sortedByDescending { conversationRecency(it.updatedAtLabel) }
        .filter { !it.agentId.isNullOrBlank() }
        .distinctBy { it.agentId }
        .map { conversation ->
            val id = conversation.agentId!!
            val conversationName = conversation.agentName.takeIf { it.isNotBlank() && it != id }
            // DisplayNames.agent so an unresolved agent reads "Agent c356b8f2",
            // never the raw id — raw ids in the rail/header look like errors
            // and are unsearchable by name.
            id to DisplayNames.agent(conversationName ?: rosterNameById[id] ?: conversation.agentName, id)
        }
    val seenIds = fromConversations.mapTo(mutableSetOf()) { it.first }
    val fromRoster = rosterAgents
        .filter { it.id.value !in seenIds }
        .map { it.id.value to DisplayNames.agent(it.name, it.id.value) }
        .sortedBy { it.second.lowercase() }
    // Final dedupe by id: the server can return the same row twice in list
    // endpoints during active runs (same defect as the conversation-list
    // fan-out), and a duplicated roster agent crashes every LazyColumn that
    // keys rows by agent id (e.g. the New Conversation directory).
    //
    // Then widen any synthetic label that repeats. The first eight characters
    // of a prefixed UUID are not unique, and the rail STACKS agents by display
    // name — two unresolved agents colliding on "Agent 12345678" merged into
    // one orb, leaving the second with no way to be opened.
    return DisplayNames.disambiguateAgentFallbacks(
        (fromConversations + fromRoster).distinctBy { it.first },
    )
}

/**
 * The selected stack's conversations under the archive filter, newest first.
 *
 * letta-mobile-5172y.2: ephemeral "Letta Code" subagent conversations are now
 * grouped by AUTHORITATIVE PARENT PROVENANCE (via the shared
 * [groupSubagentConversations] model) instead of by display name. This fixes
 * the defect where unrelated agents that merely SHARE a display name (e.g. two
 * "Letta Code" spawns from different parents) were wrongly merged into one
 * stack by the old `it.agentName == agentName` test.
 *
 * Membership resolution:
 *  - If the SELECTED conversation is a subagent conversation (it appears in one
 *    of the shared model's provenance [SubagentStack]s), the stack's members
 *    are exactly that stack's [SubagentStack.memberConversationIds]. Two
 *    same-name-but-different-parent stacks therefore stay distinct.
 *  - Otherwise the agent is a NORMAL (non-subagent) agent — its conversations
 *    are exactly the shared model's `ungrouped` list, and membership falls back
 *    to the historical display-name equality test. Behaviour is UNCHANGED for
 *    normal agents.
 *
 * The archive filter and newest-first sort are applied AFTER grouping, exactly
 * as before (the shared model does not filter archived conversations — that is
 * the consumer's job).
 */
internal data class FilterStackConversationsParams(
    val conversations: List<DesktopConversationSummary>,
    val activeSubagents: List<SubagentEntry>,
    val selectedAgentName: String,
    /**
     * The selected agent's IDENTITY, which is what normal-agent membership is
     * really keyed on. [selectedAgentName] is a rendered label — once it can be
     * a resolved or synthesised display name, comparing it against a
     * conversation's stored `agentName` (which may still be a raw id) selects
     * nothing and the sidebar goes empty. Null only when nothing is selected.
     */
    val selectedAgentId: String? = null,
    val selectedConversationId: String?,
    val archiveFilter: ConversationArchiveFilter,
)

internal fun filterStackConversations(
    params: FilterStackConversationsParams,
): List<DesktopConversationSummary> {
    val conversations = params.conversations
    val activeSubagents = params.activeSubagents
    val selectedAgentName = params.selectedAgentName
    val selectedConversationId = params.selectedConversationId
    val archiveFilter = params.archiveFilter
    val grouping = groupSubagentConversations(conversations, activeSubagents)
    val selectedStack = selectedConversationId?.let { convId ->
        grouping.stacks.firstOrNull { convId in it.memberConversationIds }
    }
    val members: List<DesktopConversationSummary> = if (selectedStack != null) {
        // Subagent stack: authoritative provenance membership (NOT name).
        val memberIds = selectedStack.memberConversationIds.toSet()
        conversations.filter { it.id in memberIds }
    } else {
        // Normal agent: membership by identity when we have it. The historical
        // display-name test only survives as a fallback for conversations the
        // server sent without an agentId — it cannot be the primary key now
        // that the selected name is a RENDERED label rather than the raw
        // string stored on the conversation.
        val selectedAgentId = params.selectedAgentId
        grouping.ungrouped.filter { conversation ->
            if (selectedAgentId != null && !conversation.agentId.isNullOrBlank()) {
                conversation.agentId == selectedAgentId
            } else {
                conversation.agentName == selectedAgentName
            }
        }
    }
    // Dedupe by id: the server can hand back the same conversation twice while
    // a run is active on it, and the sidebar LazyColumn keys rows by id — a
    // duplicate is an instant crash (seen live: Key "conv-…" already used).
    return members.distinctBy { it.id }.applyArchiveFilterNewestFirst(archiveFilter)
}

/** Apply the active/archived/all filter and sort newest-first. */
private fun List<DesktopConversationSummary>.applyArchiveFilterNewestFirst(
    archiveFilter: ConversationArchiveFilter,
): List<DesktopConversationSummary> = this
    .filter { c ->
        when (archiveFilter) {
            ConversationArchiveFilter.Active -> !c.archived
            ConversationArchiveFilter.Archived -> c.archived
            ConversationArchiveFilter.All -> true
        }
    }
    .sortedByDescending { conversationRecency(it.updatedAtLabel) }

/**
 * @mention candidates: other agents + the focused agent's memory blocks.
 * (Files need a client-side index — tracked as a follow-up.)
 */
internal data class BuildMentionablesParams(
    val railAgents: List<Pair<String, String>>,
    val memoryState: DesktopMemorySurfaceState,
)

internal fun buildMentionables(params: BuildMentionablesParams): List<Mentionable> = buildList {
    params.railAgents.forEach { (id, name) ->
        add(Mentionable(id = id, label = name, sublabel = "agent", kind = MentionKind.Agent, insertText = name))
    }
    params.memoryState.memory.sections
        .flatMap { it.items }
        .filterIsInstance<MemoryParityItem.MemoryBlock>()
        .forEach { block ->
            add(
                Mentionable(
                    id = block.id,
                    label = block.title,
                    sublabel = "core block",
                    kind = MentionKind.Memory,
                    insertText = block.title,
                ),
            )
        }
}

/** Cmd/Ctrl-K command palette over conversations, agents, and destinations. */
internal fun buildPaletteItems(
    conversations: List<DesktopConversationSummary>,
    railAgents: List<Pair<String, String>>,
    workPlayMode: WorkPlayMode,
): List<PaletteItem> = buildList {
    conversations.forEach { conversation ->
        val railIndex = railAgents.indexOfFirst { it.first == conversation.agentId }
        // Resolve the subtitle through the rail rather than parroting
        // `agentName`: that field still holds the raw id whenever name
        // resolution missed at conversation-load time, and "no raw ids in
        // user-visible text" has to hold on every surface or it holds on none.
        // The rail entry is already the disambiguated label; fall back to the
        // same helper for a conversation the rail does not know about.
        val agentLabel = railAgents.getOrNull(railIndex)?.second
            ?: DisplayNames.agent(conversation.agentName, conversation.agentId ?: conversation.agentName)
        add(
            PaletteItem(
                id = conversation.id,
                label = conversation.title,
                sublabel = agentLabel,
                kind = PaletteItemKind.Conversation,
                orbIndex = railIndex.coerceAtLeast(0),
                agentId = conversation.agentId,
            ),
        )
    }
    railAgents.forEachIndexed { index, (id, name) ->
        add(
            PaletteItem(
                id = id,
                label = name,
                sublabel = "agent",
                kind = PaletteItemKind.Agent,
                orbIndex = index,
                agentId = id,
            ),
        )
    }
    WorkPlayLens.navDestinations(workPlayMode).forEach { lensDestination ->
        val target = lensNavTarget(workPlayMode, lensDestination)
        add(
            PaletteItem(
                id = target.first.name,
                label = WorkPlayLens.destinationLabel(workPlayMode, lensDestination),
                sublabel = null,
                kind = PaletteItemKind.Destination,
            ),
        )
    }
}.distinctBy { it.kind to it.id }
// ^ Palette LazyColumns key rows by "$kind-$id"; server list endpoints can
// duplicate rows during active runs, and a duplicated item is an instant
// crash (seen live: Key "qq-Agent-agent-…" already used).

/**
 * Composer "/" palette: local navigation commands plus the focused agent's
 * server slash commands (goal mode + installed skills). Selecting a server
 * command fills the composer so the user can add args and send; the server
 * interprets the slash prefix.
 */
internal data class BuildComposerCommandsParams(
    val chatController: DesktopChatController,
    val agentSlashCommands: List<AgentSlashCommand>,
    val onCreateAgent: () -> Unit,
    val onEditAgent: () -> Unit,
    val onNavigate: (DesktopDestination) -> Unit,
    val onOpenCanvas: (() -> Unit)? = null,
)

internal fun buildComposerCommands(params: BuildComposerCommandsParams): List<ComposerCommand> = buildList {
    val chatController = params.chatController
    val onNavigate = params.onNavigate
    add(ComposerCommand("new", "Start a new chat") { chatController.createConversation() })
    add(ComposerCommand("agent", "Create a new agent") { params.onCreateAgent() })
    add(ComposerCommand("edit", "Edit this agent") { params.onEditAgent() })
    add(ComposerCommand("canvas", "Open canvas workspace") { params.onOpenCanvas?.invoke() })
    add(ComposerCommand("memory", "Open memory") { onNavigate(DesktopDestination.Memory) })
    add(ComposerCommand("schedules", "Open schedules") { onNavigate(DesktopDestination.Schedules) })
    add(ComposerCommand("skills", "Open skills & tools") { onNavigate(DesktopDestination.Agents) })
    add(ComposerCommand("channels", "Open channels") { onNavigate(DesktopDestination.Channels) })
    add(ComposerCommand("settings", "Open settings") { onNavigate(DesktopDestination.Settings) })
    params.agentSlashCommands.forEach { cmd ->
        add(
            ComposerCommand(
                label = cmd.command,
                description = cmd.description.ifBlank {
                    cmd.skillName?.let { "Skill: $it" } ?: "Slash command"
                },
                fillsComposer = true,
            ) { chatController.updateComposerText("/${cmd.command} ") },
        )
    }
}

/** Model/embedding defaults for a new agent, copied from the focused agent. */
internal fun resolveNewAgentDefaults(
    agentRepository: IAgentRepository,
    templateAgentId: String?,
    modelValue: String?,
): Pair<String?, String?> {
    val template = templateAgentId?.let { agentRepository.getCachedAgent(it) }
    return (modelValue ?: template?.model) to template?.embedding
}

internal fun conversationRecency(label: String): java.time.Instant =
    runCatching { java.time.Instant.parse(label) }.getOrNull()
        ?: if (label == "Queued") java.time.Instant.MAX else java.time.Instant.MIN

internal data class OpenDesktopCanvasParams(
    val scope: CoroutineScope,
    val store: com.letta.mobile.desktop.canvas.DesktopCanvasDocumentStore,
    val conversationId: String?,
    val agentId: String?,
    val agentName: String,
    val onSessionReady: (com.letta.mobile.data.canvas.CanvasSession) -> Unit,
)

internal fun openDesktopCanvasSession(params: OpenDesktopCanvasParams) {
    val convId = params.conversationId ?: "desktop-default-conversation"
    val displayName = if (params.agentName.isBlank()) "Conversation" else params.agentName
    params.scope.launch {
        val session = com.letta.mobile.data.canvas.CanvasSession.getOrCreateForConversation(
            store = params.store,
            conversationId = convId,
            agentId = params.agentId,
            title = "Canvas ($displayName)",
        )
        params.onSessionReady(session)
    }
}

internal data class DesktopComposerCommandsParams(
    val chatController: DesktopChatController,
    val agentSlashCommands: List<AgentSlashCommand>,
    val selectedConversationId: String?,
    val selectedAgentId: String?,
    val selectedAgentName: String,
    val selectedDestination: DesktopDestination,
    val canvasStore: com.letta.mobile.desktop.canvas.DesktopCanvasDocumentStore,
    val chatScope: CoroutineScope,
    val onNavigate: (DesktopDestination) -> Unit,
    val onCreateAgent: () -> Unit,
    val onEditAgent: (String?) -> Unit,
    val onCanvasSessionChange: (com.letta.mobile.data.canvas.CanvasSession?) -> Unit,
)

@Composable
internal fun rememberDesktopComposerCommands(params: DesktopComposerCommandsParams): List<ComposerCommand> {
    return remember(
        params.selectedConversationId,
        params.agentSlashCommands,
        params.selectedDestination,
        params.selectedAgentId,
    ) {
        buildComposerCommands(
            BuildComposerCommandsParams(
                chatController = params.chatController,
                agentSlashCommands = params.agentSlashCommands,
                onCreateAgent = params.onCreateAgent,
                onEditAgent = { params.onEditAgent(params.selectedAgentId) },
                onNavigate = params.onNavigate,
                onOpenCanvas = {
                    openDesktopCanvasSession(
                        OpenDesktopCanvasParams(
                            scope = params.chatScope,
                            store = params.canvasStore,
                            conversationId = params.selectedConversationId,
                            agentId = params.selectedAgentId,
                            agentName = params.selectedAgentName,
                            onSessionReady = params.onCanvasSessionChange,
                        ),
                    )
                },
            ),
        )
    }
}

internal data class CreateDesktopChatDetailPaneActionsParams(
    val chatController: DesktopChatController,
    val canSubmitApprovals: Boolean,
    val onA2uiAction: (com.letta.mobile.data.a2ui.A2uiAction) -> Unit,
    val onAttachImage: () -> Unit,
    val onOpenModelPicker: () -> Unit,
    val onSetPersona: () -> Unit,
    val onNavigateToChannels: () -> Unit,
    val onNavigateToAgents: () -> Unit,
    val onOpenAgent: (String) -> Unit,
    /** The composer companion mascot taps into the agent pane. */
    val onOpenAgentPane: (() -> Unit)? = null,
)

internal fun createDesktopChatDetailPaneActions(
    params: CreateDesktopChatDetailPaneActionsParams,
): ChatDetailPaneActions {
    val chatController = params.chatController
    return ChatDetailPaneActions(
        onComposerTextChanged = chatController::updateComposerText,
        onSend = chatController::send,
        onSubmitApproval = chatController::submitApproval.takeIf { params.canSubmitApprovals },
        onA2uiAction = params.onA2uiAction,
        onAttachImage = params.onAttachImage,
        onRemoveImageAttachment = chatController::removeImageAttachment,
        onRetryConnection = chatController::retryConnection,
        onModelSelected = chatController::setConversationModel,
        onChangeWorkingDirectory = chatController::changeSelectedConversationWorkingDirectory,
        onOpenModelPicker = params.onOpenModelPicker,
        onOpenAgentPane = params.onOpenAgentPane,
        onOnboardingTask = { kind ->
            when (kind) {
                OnboardingTaskKind.SetPersona -> params.onSetPersona()
                OnboardingTaskKind.ConnectChannel -> params.onNavigateToChannels()
                OnboardingTaskKind.AddSkills -> params.onNavigateToAgents()
            }
        },
        onOpenAgent = params.onOpenAgent,
    )
}

