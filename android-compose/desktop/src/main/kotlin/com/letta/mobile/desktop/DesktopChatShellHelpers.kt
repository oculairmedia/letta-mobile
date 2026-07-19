package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.groupSubagentConversations
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.desktop.avatar.DesktopAvatarCompanion
import com.letta.mobile.desktop.avatar.DesktopAvatarLibraryWindow
import com.letta.mobile.desktop.avatar.defaultAvatarCatalogDir
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksPanel
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.chat.DesktopCommandPalette
import com.letta.mobile.desktop.chat.DesktopModelPickerSheet
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.agent.agentAvatarStyleKey
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopHttpBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.skills.DesktopIrohSkillsApi
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

internal class DesktopAvatarCompanionHandle(
    val companion: DesktopAvatarCompanion,
    val state: DesktopAvatarCompanion.State,
    val toggle: () -> Unit,
) {
    val isActive: Boolean
        get() = state is DesktopAvatarCompanion.State.Running ||
            state is DesktopAvatarCompanion.State.Starting
}

/**
 * Avatar companion (pet mode v1): loopback-hosted three-vrm renderer in
 * the default browser, driven by the agent's presence via AvatarDirector.
 * Owns the avatar library window — library-first: pick from imported avatars
 * (with their license on display) instead of a blind file dialog; imports run
 * the full pipeline from inside the library window.
 */
@Composable
internal fun rememberAvatarCompanion(
    chatScope: CoroutineScope,
    secureSettingsStore: DesktopFileSecureSettingsStore,
): DesktopAvatarCompanionHandle {
    val avatarCompanion = remember(chatScope) { DesktopAvatarCompanion(chatScope) }
    val avatarCompanionState by avatarCompanion.state.collectAsState()
    DisposableEffect(avatarCompanion) {
        onDispose { avatarCompanion.stop() }
    }
    var showAvatarLibrary by remember { mutableStateOf(false) }
    var activeAvatarModelId by remember { mutableStateOf<String?>(null) }
    if (showAvatarLibrary) {
        DesktopAvatarLibraryWindow(
            catalogDir = remember { defaultAvatarCatalogDir() },
            activeModelId = activeAvatarModelId,
            onUseAvatar = { model, assetPath ->
                showAvatarLibrary = false
                secureSettingsStore.putString(AVATAR_COMPANION_VRM_PATH_KEY, assetPath.toString())
                activeAvatarModelId = model.id
                avatarCompanion.stop()
                avatarCompanion.start(assetPath)
            },
            onClose = { showAvatarLibrary = false },
        )
    }
    return DesktopAvatarCompanionHandle(
        companion = avatarCompanion,
        state = avatarCompanionState,
        toggle = {
            when (avatarCompanionState) {
                is DesktopAvatarCompanion.State.Starting,
                is DesktopAvatarCompanion.State.Running,
                -> {
                    avatarCompanion.stop()
                    activeAvatarModelId = null
                }
                else -> showAvatarLibrary = true
            }
        },
    )
}

internal data class AvatarPresenceParams(
    val avatar: DesktopAvatarCompanionHandle,
    val isStreamingReplySelected: Boolean,
    val thinkingConversationId: String?,
    val errorMessage: String?,
)

/** Agent presence -> avatar companion behavior. */
@Composable
internal fun AvatarPresenceEffects(
    avatar: DesktopAvatarCompanionHandle,
    isStreamingReplySelected: Boolean,
    thinkingConversationId: String?,
    errorMessage: String?,
) {
    AvatarPresenceEffects(
        AvatarPresenceParams(
            avatar = avatar,
            isStreamingReplySelected = isStreamingReplySelected,
            thinkingConversationId = thinkingConversationId,
            errorMessage = errorMessage,
        ),
    )
}

@Composable
internal fun AvatarPresenceEffects(params: AvatarPresenceParams) {
    val avatar = params.avatar
    LaunchedEffect(params.isStreamingReplySelected, params.thinkingConversationId, avatar.state) {
        if (avatar.isActive) {
            avatar.companion.setActivity(
                when {
                    params.isStreamingReplySelected -> AvatarActivity.SPEAKING
                    params.thinkingConversationId != null -> AvatarActivity.THINKING
                    else -> AvatarActivity.IDLE
                },
            )
        }
    }
    LaunchedEffect(params.errorMessage) {
        if (params.errorMessage != null) avatar.companion.flashError()
    }
}

/** Model picker options: display label to model handle (or name fallback). */
internal fun buildModelOptions(availableModels: List<LlmModel>): List<Pair<String, String>> =
    availableModels.map { model ->
        val label = model.displayNameOverride?.takeIf { it.isNotBlank() } ?: model.name
        val value = model.handle?.takeIf { it.isNotBlank() } ?: model.name
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
    val fromConversations = conversations
        .sortedByDescending { conversationRecency(it.updatedAtLabel) }
        .filter { !it.agentId.isNullOrBlank() }
        .distinctBy { it.agentId }
        .map { it.agentId!! to it.agentName }
    val seenIds = fromConversations.mapTo(mutableSetOf()) { it.first }
    val fromRoster = rosterAgents
        .filter { it.id.value !in seenIds }
        .map { it.id.value to it.name.ifBlank { it.id.value } }
        .sortedBy { it.second.lowercase() }
    return fromConversations + fromRoster
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
        // Normal agent: unchanged display-name membership over ungrouped convs.
        grouping.ungrouped.filter { it.agentName == selectedAgentName }
    }
    return members.applyArchiveFilterNewestFirst(archiveFilter)
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
        val orbIndex = railAgents.indexOfFirst { it.first == conversation.agentId }.coerceAtLeast(0)
        add(
            PaletteItem(
                id = conversation.id,
                label = conversation.title,
                sublabel = conversation.agentName,
                kind = PaletteItemKind.Conversation,
                orbIndex = orbIndex,
            ),
        )
    }
    railAgents.forEachIndexed { index, (id, name) ->
        add(PaletteItem(id = id, label = name, sublabel = "agent", kind = PaletteItemKind.Agent, orbIndex = index))
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
)

internal fun buildComposerCommands(params: BuildComposerCommandsParams): List<ComposerCommand> = buildList {
    val chatController = params.chatController
    val onNavigate = params.onNavigate
    add(ComposerCommand("new", "Start a new chat") { chatController.createConversation() })
    add(ComposerCommand("agent", "Create a new agent") { params.onCreateAgent() })
    add(ComposerCommand("edit", "Edit this agent") { params.onEditAgent() })
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

private const val AVATAR_COMPANION_VRM_PATH_KEY = "avatar.companion.vrm_path"



