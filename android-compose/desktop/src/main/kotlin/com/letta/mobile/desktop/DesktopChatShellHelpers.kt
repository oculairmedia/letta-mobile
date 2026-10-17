package com.letta.mobile.desktop

import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.data.shell.ShellRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.agents.RecentAgentsCut
import com.letta.mobile.data.agents.RecentAgentsInput
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.shell.rail.ShellRailActivity
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.data.model.DisplayNames
import com.letta.mobile.data.composer.AgentMentions
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.composer.MentionableAgent
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.groupSubagentConversations
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.data.DesktopDataBindings
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.ui.shell.header.UnifiedSearchDestination
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.shell.header.rememberConversationMessageIndex
import com.letta.mobile.ui.chat.session.ChatHostCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.commands.AgentSlashCommand
import kotlinx.coroutines.launch

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
 * Each agent's activity for the rail's recents cut: its newest conversation. Queued work sorts
 * newest and an unparseable label oldest, as everywhere on the rail.
 */
internal fun railAgentActivity(conversations: List<DesktopConversationSummary>): Map<String, kotlin.time.Instant> =
    RecentAgents.latestByAgent(
        conversations.mapNotNull { conversation ->
            conversation.agentId?.let { it to ShellRailMapping.recency(conversation.updatedAtLabel) }
        },
    )

/**
 * The rail is a recents strip, not the whole roster (Grok Bot's layout): the shared
 * [RecentAgents.cut] over [railAgentActivity] — the same rule the phone's drawer applies
 * (letta-mobile-c3np7.5.8). The desktop keeps no agent pins. Recomputed only when its inputs change.
 */
@Composable
internal fun rememberRecentRailAgents(
    conversations: List<DesktopConversationSummary>,
    directory: List<Pair<String, String>>,
    prefs: DesktopRailPrefs,
    selectedAgentId: String?,
): RecentAgentsCut = remember(conversations, directory, selectedAgentId, prefs.recencyDays) {
    RecentAgents.cut(
        RecentAgentsInput(directory = directory, lastActiveAt = railAgentActivity(conversations), selectedAgentId = selectedAgentId),
        kotlin.time.Clock.System.now(),
        prefs.recencyPolicy,
    )
}

/** Each agent's newest conversation, for the rail's hover card. */
@Composable
internal fun rememberRailActivityByAgentId(
    conversations: List<DesktopConversationSummary>,
): Map<String, ShellRailActivity> = remember(conversations) {
    conversations
        .filter { !it.agentId.isNullOrBlank() }
        .groupBy { it.agentId!! }
        .mapValues { (_, convs) ->
            val latest = convs.maxBy { conversationRecency(it.updatedAtLabel) }
            ShellRailActivity(updatedAtLabel = latest.updatedAtLabel, preview = latest.lastMessagePreview)
        }
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
 * @mention candidates: the other agents (an `@agent` reference their messaging tools can address,
 * letta-mobile-zxk2n.6) + the focused agent's memory blocks. Files come from the workspace search.
 */
internal data class BuildMentionablesParams(
    val railAgents: List<Pair<String, String>>,
    val memoryState: MemoryParityControllerState,
    /** The conversation's own agent, which is not offered. */
    val focusedAgentId: String? = null,
)

internal fun buildMentionables(params: BuildMentionablesParams): List<Mentionable> = buildList {
    val roster = params.railAgents.map { (id, name) -> MentionableAgent(id = id, name = name) }
    addAll(AgentMentions.mentionables(roster, excludeAgentId = params.focusedAgentId))
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

/**
 * The pages the command palette and the quick-query bar can go to; conversations, message text and
 * agents come from the unified search's own sources (letta-mobile-ckmub.3.9). The lens's sections,
 * then the fleet's admin pages, which no lens section opens (letta-mobile-ckmub.2.6). A row's id is its page's
 * shell route, which the shared search-hit opener decodes.
 */
internal fun buildPaletteDestinations(workPlayMode: WorkPlayMode): List<UnifiedSearchDestination> =
    WorkPlayLens.navDestinations(workPlayMode)
        .map { lensDestination ->
            val target = lensNavTarget(workPlayMode, lensDestination)
            UnifiedSearchDestination(
                id = ShellRoute.encode(target.first.shellPage),
                label = WorkPlayLens.destinationLabel(workPlayMode, lensDestination),
            )
        }
        .plus(PaletteAdminDestinations.map { UnifiedSearchDestination(id = ShellRoute.encode(it.shellPage), label = it.label) })
        // Picker rows are keyed by id: a page reached through two lens entries is listed once.
        .distinctBy { it.id }

private val PaletteAdminDestinations = listOf(DesktopDestination.Runs, DesktopDestination.Templates, DesktopDestination.Projects, DesktopDestination.Plugins)

/**
 * The window's one unified search, shared by the header, the command palette and the quick-query bar:
 * what the shell holds, plus message text from a host-side index over App Server reads.
 */
@Composable
internal fun rememberDesktopSearchSources(context: DesktopShellContext, conversations: List<DesktopConversationSummary>, focus: DesktopAgentFocus): UnifiedSearchSources {
    val chat = context.core.chatController
    val canvases by context.core.canvasShell.library.documents.collectAsState()
    val messages = rememberConversationMessageIndex(conversations, chat.conversationManagement.messageReader)
    val workPlayMode = context.navigator.workPlayMode
    val destinations = remember(workPlayMode) { buildPaletteDestinations(workPlayMode) }
    return remember(conversations, focus, canvases, destinations, messages) {
        UnifiedSearchSources(conversations, focus.rosterAgents, canvases, focus.avatarStyleByAgentId, focus.selectedAgentId, destinations, messages)
    }
}

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
    /** letta-mobile-zxk2n.5: the surfaces the client-local commands open. */
    val client: DesktopClientCommandRoutes = DesktopClientCommandRoutes(),
)

/** letta-mobile-zxk2n.5 (G92): where the desktop's client-local `/` commands lead that are not a page. */
internal data class DesktopClientCommandRoutes(
    /** `/search`: the command palette (mod+K), which searches chats and agents. */
    val onSearch: () -> Unit = {},
    val onOpenModelPicker: () -> Unit = {},
    val onRenameChat: () -> Unit = {},
    /** `/new`: the shell's new chat for the focused agent (which pins it when "Pin new chats" is on). */
    val onNewChat: () -> Unit = {},
)

/** The desktop's route for each [ClientSlashCommand]. */
internal fun desktopClientCommand(
    command: ClientSlashCommand,
    params: BuildComposerCommandsParams,
): ChatHostCommand {
    val controller = params.chatController
    val client = params.client
    val run: () -> Unit = when (command) {
        ClientSlashCommand.Search -> client.onSearch
        ClientSlashCommand.Memory -> { { params.onNavigate(DesktopDestination.Memory) } }
        // Skills & Tools is where the desktop lists and merges MCP servers.
        ClientSlashCommand.Mcp -> { { params.onNavigate(DesktopDestination.Agents) } }
        ClientSlashCommand.Models -> client.onOpenModelPicker
        ClientSlashCommand.New -> client.onNewChat
        ClientSlashCommand.Rename -> client.onRenameChat
        ClientSlashCommand.Connect -> { { params.onNavigate(DesktopDestination.Providers) } }
        ClientSlashCommand.Recompile -> controller.conversationManagement::recompileOpenConversation
    }
    return ChatHostCommand(command.id, command.description, run = run)
}

internal fun buildComposerCommands(params: BuildComposerCommandsParams): List<ChatHostCommand> = buildList {
    val onNavigate = params.onNavigate
    ClientSlashCommand.entries.forEach { add(desktopClientCommand(it, params)) }
    // letta-mobile-7mys7: letta-code's /fork [name], run by the client (the App Server does not).
    if (params.chatController.conversationManagement.supportsBranching) {
        add(
            ChatHostCommand("fork", "Fork this chat (/fork [name])", fillsComposer = true) {
                params.chatController.updateComposerText("/fork ")
            },
        )
    }
    add(ChatHostCommand("agent", "Create a new agent") { params.onCreateAgent() })
    add(ChatHostCommand("edit", "Edit this agent") { params.onEditAgent() })
    add(ChatHostCommand("canvas", "Open canvas workspace") { params.onOpenCanvas?.invoke() })
    add(ChatHostCommand("schedules", "Open schedules") { onNavigate(DesktopDestination.Schedules) })
    add(ChatHostCommand("skills", "Open skills & tools") { onNavigate(DesktopDestination.Agents) })
    add(ChatHostCommand("channels", "Open channels") { onNavigate(DesktopDestination.Channels) })
    add(ChatHostCommand("providers", "Open providers & models") { onNavigate(DesktopDestination.Providers) })
    add(ChatHostCommand("settings", "Open settings") { onNavigate(DesktopDestination.Settings) })
    // A server command named like a client command would shadow it (commands are keyed by label).
    params.agentSlashCommands.filter { ClientSlashCommand.fromId(it.command) == null }.forEach { cmd ->
        add(
            ChatHostCommand(
                label = cmd.command,
                description = cmd.description.ifBlank {
                    cmd.skillName?.let { "Skill: $it" } ?: "Slash command"
                },
                fillsComposer = true,
            ) { params.chatController.updateComposerText("/${cmd.command} ") },
        )
    }
}

/** Model/embedding defaults for a new agent, copied from the focused agent. */
internal fun conversationRecency(label: String): java.time.Instant =
    runCatching { java.time.Instant.parse(label) }.getOrNull()
        ?: if (label == "Queued") java.time.Instant.MAX else java.time.Instant.MIN

internal data class OpenDesktopCanvasParams(
    val scope: CoroutineScope,
    val store: com.letta.mobile.data.canvas.CanvasDocumentStore,
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
            conversationId = ConversationId(convId),
            options = com.letta.mobile.data.canvas.CanvasConversationOptions(
                agentId = params.agentId,
                title = "Canvas ($displayName)",
                opLog = com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.opLog,
                syncTransport = com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.syncTransport,
            ),
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
    val canvasStore: com.letta.mobile.data.canvas.CanvasDocumentStore,
    val chatScope: CoroutineScope,
    val onNavigate: (DesktopDestination) -> Unit,
    val onCreateAgent: () -> Unit,
    val onEditAgent: (String?) -> Unit,
    val onCanvasSessionChange: (com.letta.mobile.data.canvas.CanvasSession?) -> Unit,
    /** Set while the shared chat page docks the conversation's board: the canvas command shows it. */
    val showDockedCanvas: (() -> Unit)? = null,
    val client: DesktopClientCommandRoutes = DesktopClientCommandRoutes(),
)

@Composable
internal fun rememberDesktopComposerCommands(params: DesktopComposerCommandsParams): List<ChatHostCommand> {
    return remember(
        params.selectedConversationId,
        params.agentSlashCommands,
        params.selectedDestination,
        params.selectedAgentId,
        params.showDockedCanvas != null,
    ) {
        val showDocked = params.showDockedCanvas
        buildComposerCommands(
            BuildComposerCommandsParams(
                chatController = params.chatController,
                agentSlashCommands = params.agentSlashCommands,
                onCreateAgent = params.onCreateAgent,
                onEditAgent = { params.onEditAgent(params.selectedAgentId) },
                onNavigate = params.onNavigate,
                client = params.client,
                onOpenCanvas = showDocked ?: {
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

internal fun handleDesktopShareCanvasToChat(
    bytes: ByteArray,
    mimeType: String,
    chatController: DesktopChatController,
    onSuccessNav: () -> Unit,
) {
    com.letta.mobile.data.canvas.CanvasShare.packageForChat(
        bytes,
        com.letta.mobile.data.canvas.CanvasMimeType.fromValue(mimeType),
    )
        .onSuccess { image ->
            chatController.attachImage(image)
            onSuccessNav()
        }
        .onFailure { error ->
            chatController.showComposerError(error.message ?: "Could not share canvas to chat")
        }
}

internal data class CreateDesktopOverlayActionsParams(
    val chatController: DesktopChatController,
    val onSelectDestination: (DesktopDestination) -> Unit,
    val onOpenAgent: (String) -> Unit,
    /** The host's model control lives here. */
    val dataBindings: DesktopDataBindings,
    /** Opens the shared agent editor's new-agent flow (letta-mobile-ckmub.3.24). */
    val onNewAgent: () -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onNewCanvas: () -> Unit = {},
    /** Opens a command-palette pick (the unified search's hit). */
    val onOpenSearchHit: (UnifiedSearchHit) -> Unit = {},
)

internal fun createDesktopOverlayActions(
    params: CreateDesktopOverlayActionsParams,
): DesktopOverlayActions = DesktopOverlayActions(
    modelControl = DesktopModelControlHost(
        session = params.dataBindings.modelControl,
        chatModels = params.chatController.models.available,
        reloadChatModels = params.chatController.models::reload,
        onModelSelected = params.chatController.models::select,
        recentModels = params.chatController.conversationManagement.recentModels,
    ),
    onNavigate = params.onSelectDestination,
    onIrohIdentityReset = params.onIrohIdentityReset,
    workspace = ShellWorkspaceOverlayActions(
        onOpenAgent = params.onOpenAgent,
        onNewAgent = params.onNewAgent,
        onOpenSearchHit = params.onOpenSearchHit,
        onNewCanvas = params.onNewCanvas,
    ),
)

