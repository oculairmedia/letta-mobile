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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.AgentSphere
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.desktop.schedules.DesktopCronApi
import com.letta.mobile.desktop.schedules.DesktopCronTask
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleLibrarySurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.desktop.tools.DesktopToolLibrarySurface
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

private const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L

@Composable
fun LettaDesktopApp(
    onActiveTitleChange: (String) -> Unit = {},
) {
    var selectedDestination by rememberSaveable { mutableStateOf(DesktopDestination.Conversations) }
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var editAgentId by remember { mutableStateOf<String?>(null) }
    val secureSettingsStore = remember { DesktopFileSecureSettingsStore() }
    val configStore = remember(secureSettingsStore) { DesktopLettaConfigStore(secureSettingsStore) }
    var activeConfig by remember { mutableStateOf(configStore.load()) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
        )
    }
    var bootstrapState by remember(dataBindings) {
        mutableStateOf(defaultDesktopBootstrapState(dataBindings, activeConfig))
    }
    val chatScope = rememberCoroutineScope()
    val chatController = remember(bootstrapState, chatScope, dataBindings.sessionGraphProvider) {
        DesktopChatController(
            bootstrapState = bootstrapState,
            scope = chatScope,
            agentNamesByIdProvider = { agentIds ->
                val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
                runCatching {
                    agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
                }
                val resolved = mutableMapOf<String, String>()
                // Seed from the cached list (fast path).
                agentRepository.agents.value.forEach { agent ->
                    agent.name.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
                }
                // For any conversation agent still unresolved, fetch it directly
                // (robust against list pagination / partial caches).
                agentIds.filter { it !in resolved }.forEach { id ->
                    val name = agentRepository.getCachedAgent(id)?.name
                        ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.name
                    name?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
                }
                resolved
            },
            agentModelByIdProvider = { agentIds ->
                val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
                val resolved = mutableMapOf<String, String>()
                agentRepository.agents.value.forEach { agent ->
                    agent.model?.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
                }
                agentIds.filter { it !in resolved }.forEach { id ->
                    val model = agentRepository.getCachedAgent(id)?.model
                        ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.model
                    model?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
                }
                resolved
            },
        )
    }
    val chatState by chatController.state.collectAsState()
    val availableModels by chatController.availableModels.collectAsState()
    val deletingConversationIds by chatController.deletingConversationIds.collectAsState()
    val modelOptions = remember(availableModels) {
        availableModels.map { model ->
            val label = model.displayNameOverride?.takeIf { it.isNotBlank() }
                ?: model.name
            val value = model.handle?.takeIf { it.isNotBlank() } ?: model.name
            label to value
        }
    }
    val blockApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { DesktopBlockApi(it) }
    }
    val cronApi = remember(activeConfig) {
        activeConfig.takeIf { it.serverUrl.isNotBlank() }?.let { DesktopCronApi(it) }
    }
    var allCrons by remember(cronApi) { mutableStateOf<List<DesktopCronTask>>(emptyList()) }
    val memoryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopMemoryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val memoryState by memoryController.state.collectAsState()
    val scheduleLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopScheduleLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val scheduleLibraryState by scheduleLibraryController.state.collectAsState()
    val channelLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopChannelLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val channelLibraryState by channelLibraryController.state.collectAsState()
    val toolLibraryController = remember(bootstrapState.sessionGraphId, chatScope) {
        DesktopToolLibraryController(
            sessionGraphProvider = dataBindings.sessionGraphProvider,
            scope = chatScope,
        )
    }
    val toolLibraryState by toolLibraryController.state.collectAsState()
    val imageAttachmentLoader = remember { DesktopImageAttachmentLoader() }
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Single,
        dialogSettings = FileKitDialogSettings(title = "Attach image"),
    ) { file ->
        if (file != null) {
            chatScope.launch {
                runCatching {
                    val path = file.file.toPath()
                    imageAttachmentLoader.load(path)
                }.onSuccess(chatController::attachImage)
                    .onFailure {
                        chatController.showComposerError(
                            it.message ?: it::class.simpleName ?: "Could not attach image",
                        )
                    }
            }
        }
    }

    LaunchedEffect(chatController) {
        chatController.start()
    }
    DisposableEffect(chatController) {
        onDispose { chatController.close() }
    }
    LaunchedEffect(memoryController) {
        memoryController.start()
    }
    LaunchedEffect(scheduleLibraryController) {
        scheduleLibraryController.start()
    }
    LaunchedEffect(channelLibraryController) {
        channelLibraryController.start()
    }
    LaunchedEffect(toolLibraryController) {
        toolLibraryController.start()
    }
    LaunchedEffect(selectedDestination, chatState.selectedConversation?.agentId, memoryController) {
        if (selectedDestination == DesktopDestination.Memory) {
            chatState.selectedConversation?.agentId?.let(memoryController::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, chatState.selectedConversation?.agentId, scheduleLibraryController) {
        if (selectedDestination == DesktopDestination.Schedules) {
            chatState.selectedConversation?.agentId?.let(scheduleLibraryController::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, cronApi) {
        if (selectedDestination == DesktopDestination.Schedules && cronApi != null) {
            allCrons = runCatching { cronApi.listCrons() }.getOrDefault(emptyList())
        }
    }
    DisposableEffect(memoryController) {
        onDispose { memoryController.close() }
    }
    DisposableEffect(scheduleLibraryController) {
        onDispose { scheduleLibraryController.close() }
    }
    DisposableEffect(channelLibraryController) {
        onDispose { channelLibraryController.close() }
    }
    DisposableEffect(toolLibraryController) {
        onDispose { toolLibraryController.close() }
    }

    val activeTitle = when (selectedDestination) {
        DesktopDestination.Conversations ->
            chatState.selectedConversation?.title ?: "Letta Desktop"
        else -> selectedDestination.label
    }
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }

    // Distinct agents (by id, since many agents share a display name) that have
    // conversations — these are the rail orbs. Conversations in the sidebar are
    // filtered to the currently-selected agent only.
    val railAgents = remember(chatState.conversations) {
        chatState.conversations
            .filter { !it.agentId.isNullOrBlank() }
            .distinctBy { it.agentId }
            .map { it.agentId!! to it.agentName }
    }
    val selectedAgentId = chatState.selectedConversation?.agentId
        ?: railAgents.firstOrNull()?.first
    val selectedAgentOrbIndex = railAgents.indexOfFirst { it.first == selectedAgentId }.coerceAtLeast(0)
    val selectedAgentName = railAgents.firstOrNull { it.first == selectedAgentId }?.second
        ?: chatState.selectedConversation?.agentName ?: "Letta"
    val agentConversations = remember(chatState.conversations, selectedAgentId) {
        chatState.conversations.filter { it.agentId == selectedAgentId }
    }
    // The active conversation/agent is "thinking" while a send/run is streaming.
    val thinkingConversationId = chatState.selectedConversationId?.takeIf { chatState.isSending }
    val thinkingAgentId = selectedAgentId?.takeIf { chatState.isSending }

    DesktopMaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
          Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // Far-left workspace/agent rail.
                DesktopAgentRail(
                    agents = railAgents,
                    selectedAgentId = selectedAgentId,
                    thinkingAgentId = thinkingAgentId,
                    onAgentSelected = { agentId ->
                        chatState.conversations
                            .firstOrNull { it.agentId == agentId }
                            ?.let { chatController.selectConversation(it.id) }
                        selectedDestination = DesktopDestination.Conversations
                    },
                    onNewSession = { showNewAgentDialog = true },
                    onSearch = { selectedDestination = DesktopDestination.Conversations },
                    onSettings = { selectedDestination = DesktopDestination.Settings },
                )
                RailDivider()
                // Agent sidebar: agent header + nav + conversations.
                DesktopAgentSidebar(
                    agentName = selectedAgentName,
                    agentOrbIndex = selectedAgentOrbIndex,
                    conversations = agentConversations,
                    selectedConversationId = chatState.selectedConversationId,
                    thinkingConversationId = thinkingConversationId,
                    deletingConversationIds = deletingConversationIds,
                    selectedDestination = selectedDestination,
                    onDestinationSelected = { selectedDestination = it },
                    onConversationSelected = {
                        chatController.selectConversation(it)
                        selectedDestination = DesktopDestination.Conversations
                    },
                    onDeleteConversation = chatController::deleteConversation,
                    onNewChat = {
                        selectedDestination = DesktopDestination.Conversations
                        chatController.createConversation()
                    },
                    onEditAgent = { editAgentId = selectedAgentId },
                )
                RailDivider()
                // Main content pane.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedDestination == DesktopDestination.Conversations) {
                        val composerCommands = listOf(
                            ComposerCommand("new", "Start a new chat") { chatController.createConversation() },
                            ComposerCommand("agent", "Create a new agent") { showNewAgentDialog = true },
                            ComposerCommand("edit", "Edit this agent") { editAgentId = selectedAgentId },
                            ComposerCommand("memory", "Open memory") { selectedDestination = DesktopDestination.Memory },
                            ComposerCommand("schedules", "Open schedules") { selectedDestination = DesktopDestination.Schedules },
                            ComposerCommand("skills", "Open skills & tools") { selectedDestination = DesktopDestination.Agents },
                            ComposerCommand("channels", "Open channels") { selectedDestination = DesktopDestination.Channels },
                            ComposerCommand("settings", "Open settings") { selectedDestination = DesktopDestination.Settings },
                        )
                        ChatDetailPane(
                            state = chatState,
                            modelOptions = modelOptions,
                            onComposerTextChanged = chatController::updateComposerText,
                            onSend = chatController::send,
                            onAttachImage = { pickerLauncher.launch() },
                            onRemoveImageAttachment = chatController::removeImageAttachment,
                            onRetryConnection = chatController::retryConnection,
                            onModelSelected = chatController::setConversationModel,
                            commands = composerCommands,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DestinationContent(
                            destination = selectedDestination,
                            state = bootstrapState,
                            chatState = chatState,
                            memoryState = memoryState,
                            scheduleLibraryState = scheduleLibraryState,
                            channelLibraryState = channelLibraryState,
                            toolLibraryState = toolLibraryState,
                            onChatConversationSelected = chatController::selectConversation,
                            onChatConversationDeleted = chatController::deleteConversation,
                            onChatComposerTextChanged = chatController::updateComposerText,
                            onChatSend = chatController::send,
                            onChatAttachImage = { pickerLauncher.launch() },
                            onChatRemoveImageAttachment = chatController::removeImageAttachment,
                            onChatRetryConnection = chatController::retryConnection,
                            onMemoryRefresh = memoryController::reload,
                            onMemoryAgentSelected = memoryController::selectAgent,
                            onSchedulesRefresh = scheduleLibraryController::reload,
                            onScheduleAgentSelected = scheduleLibraryController::selectAgent,
                            onChannelsRefresh = channelLibraryController::refresh,
                            onToolsRefresh = toolLibraryController::reload,
                            onToolsSearchQueryChanged = toolLibraryController::updateSearchQuery,
                            onToolsTagToggled = toolLibraryController::toggleTag,
                            onToolsClearTags = toolLibraryController::clearTags,
                            onToolsLoadMore = toolLibraryController::loadMore,
                            onConfigSaved = { nextConfig ->
                                configStore.save(nextConfig)
                                activeConfig = configStore.load()
                                dataBindings.sessionGraphProvider.rebuild()
                                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
                            },
                            onTokenCleared = {
                                val nextConfig = activeConfig.copy(accessToken = null)
                                configStore.save(nextConfig)
                                activeConfig = configStore.load()
                                dataBindings.sessionGraphProvider.rebuild()
                                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
                            },
                            blockApi = blockApi,
                            crons = allCrons,
                            onDeleteCron = { id ->
                                chatScope.launch {
                                    cronApi?.let {
                                        runCatching { it.deleteCron(id) }
                                        allCrons = runCatching { it.listCrons() }.getOrDefault(emptyList())
                                    }
                                }
                            },
                            canCreateCron = cronApi != null &&
                                (scheduleLibraryState.selectedAgentId != null || selectedAgentId != null),
                            onCreateCron = { name, prompt, cron, recurring, tz ->
                                val targetAgent = scheduleLibraryState.selectedAgentId ?: selectedAgentId
                                if (cronApi != null && targetAgent != null) {
                                    chatScope.launch {
                                        runCatching {
                                            cronApi.createCron(
                                                agentId = targetAgent,
                                                name = name,
                                                description = name,
                                                prompt = prompt,
                                                cron = cron,
                                                timezone = tz,
                                                recurring = recurring,
                                            )
                                        }
                                        allCrons = runCatching { cronApi.listCrons() }.getOrDefault(emptyList())
                                    }
                                }
                            },
                            focusedAgentId = selectedAgentId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            val editingAgentId = editAgentId
            if (editingAgentId != null) {
                EditAgentDialog(
                    agentId = editingAgentId,
                    modelOptions = modelOptions,
                    agentRepository = dataBindings.sessionGraphProvider.current.agentRepository,
                    scope = chatScope,
                    onDismiss = { editAgentId = null },
                    onSaved = {
                        editAgentId = null
                        chatController.retryConnection()
                    },
                )
            }
            if (showNewAgentDialog) {
                NewAgentDialog(
                    modelOptions = modelOptions,
                    onDismiss = { showNewAgentDialog = false },
                    onCreate = { name, modelValue ->
                        showNewAgentDialog = false
                        val template = selectedAgentId?.let {
                            dataBindings.sessionGraphProvider.current.agentRepository.getCachedAgent(it)
                        }
                        val model = modelValue ?: template?.model
                        chatController.createAgent(
                            name = name,
                            model = model,
                            embedding = template?.embedding,
                        )
                        selectedDestination = DesktopDestination.Conversations
                    },
                )
            }
          }
        }
    }
}

/**
 * Modal for creating a new agent: name + optional model, created with base
 * tools and default memory blocks (model/embedding default to the active
 * agent's config so the new agent is valid for this backend).
 */
@Composable
private fun NewAgentDialog(
    modelOptions: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCreate: (name: String, model: String?) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("New agent")) }
    var modelValue by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first ?: "Same as current"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(420.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "New agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JewelTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { modelMenuOpen = true },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(modelLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (modelMenuOpen) {
                        JewelPopupMenu(
                            onDismissRequest = { modelMenuOpen = false; true },
                            horizontalAlignment = Alignment.Start,
                        ) {
                            selectableItem(selected = modelValue == null, onClick = { modelMenuOpen = false; modelValue = null }) {
                                DesktopControlText("Same as current")
                            }
                            modelOptions.forEach { (label, value) ->
                                selectableItem(selected = modelValue == value, onClick = { modelMenuOpen = false; modelValue = value }) {
                                    DesktopControlText(label)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(onClick = { onCreate(name.text.trim(), modelValue) }) {
                        DesktopButtonContent("Create agent")
                    }
                }
            }
        }
    }
}

/**
 * Modal for editing an existing agent: name, model, and system prompt, applied
 * via the real agent repository (PATCH /v1/agents/{id}).
 */
@Composable
private fun EditAgentDialog(
    agentId: String,
    modelOptions: List<Pair<String, String>>,
    agentRepository: IAgentRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember(agentId) { mutableStateOf(TextFieldValue("")) }
    var modelValue by remember(agentId) { mutableStateOf<String?>(null) }
    var system by remember(agentId) { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var loading by remember(agentId) { mutableStateOf(true) }
    var busy by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(agentId) {
        val agent = agentRepository.getCachedAgent(agentId)
            ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
        if (agent != null) {
            name = TextFieldValue(agent.name)
            modelValue = agent.model
            system = agent.system.orEmpty()
        } else {
            error = "Could not load agent"
        }
        loading = false
    }
    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first
        ?: modelValue ?: "Default"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(480.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Edit agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (loading) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JewelTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())

                    Text("Model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { modelMenuOpen = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(modelLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (modelMenuOpen) {
                            JewelPopupMenu(onDismissRequest = { modelMenuOpen = false; true }, horizontalAlignment = Alignment.Start) {
                                modelOptions.forEach { (label, value) ->
                                    selectableItem(selected = modelValue == value, onClick = { modelMenuOpen = false; modelValue = value }) {
                                        DesktopControlText(label)
                                    }
                                }
                            }
                        }
                    }

                    Text("System prompt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DesktopTextArea(
                        value = system,
                        onValueChange = { system = it },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        placeholder = "System prompt…",
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    DesktopOutlinedButton(onClick = onDismiss, enabled = !busy) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                runCatching {
                                    agentRepository.updateAgent(
                                        AgentId(agentId),
                                        AgentUpdateParams(
                                            name = name.text.trim().takeIf { it.isNotBlank() },
                                            model = modelValue,
                                            system = system.takeIf { it.isNotBlank() },
                                        ),
                                    )
                                }
                                    .onSuccess { onSaved() }
                                    .onFailure { error = it.message ?: "Save failed"; busy = false }
                            }
                        },
                        enabled = !busy && !loading,
                    ) { DesktopButtonContent(if (busy) "Saving…" else "Save") }
                }
            }
        }
    }
}

/**
 * Format an ISO-8601 instant (e.g. lastMessageAt) as a compact relative label
 * (now / 5m / 2h / 4d / 3w / 2mo). Non-ISO values are returned unchanged.
 */
private fun formatRelativeTimestamp(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        seconds < 2_592_000 -> "${seconds / 604_800}w"
        else -> "${seconds / 2_592_000}mo"
    }
}

@Composable
private fun RailDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Pulsing teal ring used as the agent "thinking" indicator. */
@Composable
private fun ThinkingRing(
    diameter: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinkingAlpha",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(cornerRadius)),
    )
}

/**
 * Far-left workspace/agent rail (Penpot "App Mockups v2", 56.dp wide, #0A0A0A):
 * a "+" new-session button, a stack of gradient agent orbs (one per agent), and
 * search/settings/identity actions pinned to the bottom.
 */
@Composable
private fun DesktopAgentRail(
    agents: List<Pair<String, String>>,
    selectedAgentId: String?,
    thinkingAgentId: String?,
    onAgentSelected: (String) -> Unit,
    onNewSession: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DesktopTooltip(text = "New session") {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .clickable(onClick = onNewSession),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "New session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        agents.forEachIndexed { index, (agentId, name) ->
            val selected = agentId == selectedAgentId
            val thinking = agentId == thinkingAgentId
            DesktopTooltip(text = if (thinking) "$name · thinking…" else name) {
                Box(
                    modifier = Modifier.size(width = 46.dp, height = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(width = 3.dp, height = 28.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                    }
                    if (thinking) {
                        ThinkingRing(diameter = 44.dp, cornerRadius = 10.dp)
                    }
                    AgentOrb(
                        index = index,
                        size = 36.dp,
                        modifier = Modifier.clickable { onAgentSelected(agentId) },
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        RailActionIcon(Icons.Outlined.Search, "Search", onSearch)
        RailActionIcon(Icons.Outlined.Settings, "Settings", onSettings)
        RailActionIcon(Icons.Outlined.AccountCircle, "Account", onSettings)
    }
}

@Composable
private fun RailActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    DesktopTooltip(text = description) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Agent sidebar (231.dp, #0D0D0D): the active agent header, per-agent
 * navigation (Memory/Schedules/Channels/Skills/New chat), then the pinned
 * conversation list and a Documents section.
 */
@Composable
private fun DesktopAgentSidebar(
    agentName: String,
    agentOrbIndex: Int,
    conversations: List<DesktopConversationSummary>,
    selectedConversationId: String?,
    thinkingConversationId: String?,
    deletingConversationIds: Set<String> = emptySet(),
    selectedDestination: DesktopDestination,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onEditAgent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(231.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Agent header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 2.dp, bottom = 16.dp),
        ) {
            AgentOrb(index = agentOrbIndex, size = 30.dp, cornerRadius = 6.dp)
            Text(
                text = agentName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Agent menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { menuOpen = true },
                )
                if (menuOpen) {
                    JewelPopupMenu(
                        onDismissRequest = {
                            menuOpen = false
                            true
                        },
                        horizontalAlignment = Alignment.End,
                    ) {
                        selectableItem(selected = false, onClick = { menuOpen = false; onNewChat() }) {
                            DesktopControlText("New chat")
                        }
                        selectableItem(selected = false, onClick = { menuOpen = false; onEditAgent() }) {
                            DesktopControlText("Edit agent")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Memory) },
                        ) {
                            DesktopControlText("Memory")
                        }
                        selectableItem(
                            selected = false,
                            onClick = { menuOpen = false; onDestinationSelected(DesktopDestination.Settings) },
                        ) {
                            DesktopControlText("Settings")
                        }
                    }
                }
            }
        }

        DesktopNavRow(
            label = "Memory",
            icon = Icons.Outlined.Psychology,
            selected = selectedDestination == DesktopDestination.Memory,
            onClick = { onDestinationSelected(DesktopDestination.Memory) },
        )
        DesktopNavRow(
            label = "Schedules",
            icon = Icons.Outlined.Schedule,
            selected = selectedDestination == DesktopDestination.Schedules,
            onClick = { onDestinationSelected(DesktopDestination.Schedules) },
        )
        DesktopNavRow(
            label = "Channels",
            icon = Icons.Outlined.Hub,
            selected = selectedDestination == DesktopDestination.Channels,
            onClick = { onDestinationSelected(DesktopDestination.Channels) },
        )
        DesktopNavRow(
            label = "Skills",
            icon = Icons.Outlined.Build,
            selected = selectedDestination == DesktopDestination.Agents,
            onClick = { onDestinationSelected(DesktopDestination.Agents) },
        )
        DesktopNavRow(
            label = "New chat",
            icon = Icons.Outlined.Edit,
            selected = false,
            onClick = onNewChat,
        )

        // Pinned conversations.
        SidebarSection("Pinned")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = conversations, key = { it.id }) { conversation ->
                SidebarConversationRow(
                    title = conversation.title,
                    timeLabel = formatRelativeTimestamp(conversation.updatedAtLabel),
                    selected = selectedDestination == DesktopDestination.Conversations &&
                        conversation.id == selectedConversationId,
                    thinking = conversation.id == thinkingConversationId,
                    deleting = conversation.id in deletingConversationIds,
                    onClick = { onConversationSelected(conversation.id) },
                    onDelete = { onDeleteConversation(conversation.id) },
                )
            }
            item {
                SidebarSection("Documents")
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "No chats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }

        DesktopNavRow(
            label = "Settings",
            icon = Icons.Outlined.Settings,
            selected = selectedDestination == DesktopDestination.Settings,
            onClick = { onDestinationSelected(DesktopDestination.Settings) },
        )
    }
}

@Composable
private fun SidebarConversationRow(
    title: String,
    timeLabel: String,
    selected: Boolean,
    thinking: Boolean,
    deleting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val container = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
    // While thinking, the conversation icon pulses in the primary (teal) color.
    val pulseAlpha = if (thinking) {
        val transition = rememberInfiniteTransition(label = "convThinking")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "convThinkingAlpha",
        ).value
    } else {
        1f
    }
    val iconColor = when {
        thinking -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable(enabled = !deleting, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = if (thinking) Icons.Outlined.Autorenew else Icons.Outlined.ChatBubbleOutline,
                    contentDescription = if (thinking) "thinking" else null,
                    tint = iconColor,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (deleting) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                deleting -> Text(
                    text = "Deleting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // Hover (or an open menu) swaps the timestamp for an overflow menu.
                hovered || menuOpen -> Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Conversation menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { menuOpen = true },
                    )
                    if (menuOpen) {
                        JewelPopupMenu(
                            onDismissRequest = {
                                menuOpen = false
                                true
                            },
                            horizontalAlignment = Alignment.End,
                        ) {
                            selectableItem(
                                selected = false,
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            ) {
                                DesktopControlText("Delete chat")
                            }
                        }
                    }
                }
                else -> Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }

    if (confirmDelete) {
        DesktopConfirmDialog(
            title = "Delete chat?",
            message = "\"$title\" will be permanently removed. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Destructive-action confirmation as a real, separate desktop window (it "pops
 * out" of the app rather than dimming the page like a mobile sheet).
 */
@Composable
private fun DesktopConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogState = rememberDialogState(size = DpSize(420.dp, 210.dp))
    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        title = title,
        undecorated = true,
        resizable = false,
    ) {
        val windowScope = this
        DesktopMaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom dark title bar — the native OS chrome is hidden.
                    with(windowScope) {
                        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 38.dp)
                                        .clickable(onClick = onDismiss),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                            DesktopDefaultButton(onClick = onConfirm) { DesktopButtonContent(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSection(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun DesktopNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    subdued: Boolean = false,
    tooltip: String = label,
) {
    val content = when {
        selected -> MaterialTheme.colorScheme.onSurface
        subdued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DesktopTooltip(text = tooltip) {
        JewelSimpleListItem(
            selected = selected,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            height = 34.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JewelIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = content,
                )
                JewelText(
                    text = label,
                    color = content,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val DesktopDestination.icon: ImageVector
    get() = when (this) {
        DesktopDestination.Overview -> Icons.Outlined.Dashboard
        DesktopDestination.Agents -> Icons.Outlined.SmartToy
        DesktopDestination.Memory -> Icons.Outlined.Memory
        DesktopDestination.Schedules -> Icons.Outlined.Schedule
        DesktopDestination.Channels -> Icons.Outlined.Hub
        DesktopDestination.Conversations -> Icons.Outlined.Forum
        DesktopDestination.Settings -> Icons.Outlined.Settings
    }

@Composable
private fun DestinationContent(
    destination: DesktopDestination,
    state: DesktopBootstrapState,
    chatState: DesktopChatSurfaceState,
    memoryState: DesktopMemorySurfaceState,
    scheduleLibraryState: DesktopScheduleLibraryState,
    channelLibraryState: DesktopChannelLibraryState,
    toolLibraryState: DesktopToolLibraryState,
    onChatConversationSelected: (String) -> Unit,
    onChatConversationDeleted: (String) -> Unit,
    onChatComposerTextChanged: (String) -> Unit,
    onChatSend: () -> Unit,
    onChatAttachImage: () -> Unit,
    onChatRemoveImageAttachment: (Int) -> Unit,
    onChatRetryConnection: () -> Unit,
    onMemoryRefresh: () -> Unit,
    onMemoryAgentSelected: (String) -> Unit,
    onSchedulesRefresh: () -> Unit,
    onScheduleAgentSelected: (String) -> Unit,
    onChannelsRefresh: () -> Unit,
    onToolsRefresh: () -> Unit,
    onToolsSearchQueryChanged: (String) -> Unit,
    onToolsTagToggled: (String) -> Unit,
    onToolsClearTags: () -> Unit,
    onToolsLoadMore: () -> Unit,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
    blockApi: DesktopBlockApi?,
    crons: List<DesktopCronTask>,
    onDeleteCron: (String) -> Unit,
    canCreateCron: Boolean,
    onCreateCron: (name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit,
    focusedAgentId: String?,
    modifier: Modifier = Modifier,
) {
    if (destination == DesktopDestination.Memory) {
        DesktopMemorySurface(
            state = memoryState,
            onRefresh = onMemoryRefresh,
            onAgentSelected = onMemoryAgentSelected,
            modifier = modifier,
            blockApi = blockApi,
            onBlockChanged = onMemoryRefresh,
        )
        return
    }
    if (destination == DesktopDestination.Schedules) {
        DesktopScheduleLibrarySurface(
            state = scheduleLibraryState,
            onRefresh = onSchedulesRefresh,
            onAgentSelected = onScheduleAgentSelected,
            modifier = modifier,
            crons = crons,
            focusedAgentId = focusedAgentId,
            onDeleteCron = onDeleteCron,
            canCreate = canCreateCron,
            onCreateCron = onCreateCron,
        )
        return
    }
    if (destination == DesktopDestination.Channels) {
        DesktopChannelLibrarySurface(
            state = channelLibraryState,
            onRefresh = onChannelsRefresh,
            modifier = modifier,
        )
        return
    }
    if (destination == DesktopDestination.Agents) {
        DesktopToolLibrarySurface(
            state = toolLibraryState,
            onRefresh = onToolsRefresh,
            onSearchQueryChanged = onToolsSearchQueryChanged,
            onTagToggled = onToolsTagToggled,
            onClearTags = onToolsClearTags,
            onLoadMore = onToolsLoadMore,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = destination.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (destination) {
            DesktopDestination.Overview -> {
                item { BackendCard(state.config) }
                item { StartupReadinessCard(state.featureReadiness) }
            }
            DesktopDestination.Agents -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Memory -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Schedules -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Channels -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Conversations -> {
                // Rendered by the full-height branch above.
            }
            DesktopDestination.Settings -> {
                item {
                    BackendSettingsCard(
                        config = state.config,
                        onConfigSaved = onConfigSaved,
                        onTokenCleared = onTokenCleared,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendCard(config: LettaConfig) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = "Default backend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = config.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = config.mode.label,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
                StatusPill(
                    text = "Shared model layer",
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
                StatusPill(
                    text = "Windows JVM",
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
            }
        }
    }
}

@Composable
private fun BackendSettingsCard(
    config: LettaConfig,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
) {
    var serverUrl by remember(config.id, config.serverUrl) { mutableStateOf(TextFieldValue(config.serverUrl)) }
    var tokenInput by remember(config.id) { mutableStateOf(TextFieldValue("")) }
    var mode by remember(config.id) { mutableStateOf(config.mode) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Backend",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            DesktopSettingsFieldLabel("Server URL")
            JewelTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = { JewelText("https://app.letta.com") },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopSettingsFieldLabel("Mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LettaConfig.Mode.entries.forEach { option ->
                        DesktopRadioChip(
                            selected = mode == option,
                            onClick = { mode = option },
                        ) {
                            DesktopControlText(option.label)
                        }
                    }
                }
            }
            DesktopSettingsFieldLabel("Access token")
            JewelTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                placeholder = {
                    JewelText(if (config.accessToken == null) "Optional" else "Saved token hidden")
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopDefaultButton(
                    onClick = {
                        val normalizedUrl = serverUrl.text.trim()
                        onConfigSaved(
                            LettaConfig(
                                id = desktopConfigIdFor(normalizedUrl),
                                mode = mode,
                                serverUrl = normalizedUrl,
                                accessToken = tokenInput.text.trim().takeIf { it.isNotBlank() }
                                    ?: config.accessToken,
                            ),
                        )
                        tokenInput = TextFieldValue("")
                    },
                ) {
                    DesktopButtonContent("Save")
                }
                if (config.accessToken != null) {
                    DesktopOutlinedButton(
                        onClick = {
                            tokenInput = TextFieldValue("")
                            onTokenCleared()
                        },
                    ) {
                        DesktopButtonContent("Clear token")
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingsFieldLabel(text: String) {
    JewelText(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private val LettaConfig.Mode.label: String
    get() = when (this) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> "Self-hosted"
        LettaConfig.Mode.LOCAL -> "Local runtime"
    }

@Composable
private fun StartupReadinessCard(featureReadiness: List<DesktopFeatureReadiness>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Startup readiness",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            featureReadiness.forEach { feature ->
                ReadinessRow(feature)
            }
        }
    }
}

@Composable
private fun ReadinessRow(feature: DesktopFeatureReadiness) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .background(feature.state.color(), MaterialTheme.shapes.small),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(
                    text = feature.state.label,
                    containerColor = feature.state.color().copy(alpha = 0.12f),
                    contentColor = feature.state.color(),
                    borderColor = Color.Transparent,
                )
            }
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopFeatureState.color(): Color = when (this) {
    DesktopFeatureState.Ready -> MaterialTheme.colorScheme.primary
    DesktopFeatureState.InProgress -> MaterialTheme.colorScheme.tertiary
    DesktopFeatureState.AndroidOnly -> MaterialTheme.colorScheme.secondary
}

private val DesktopFeatureState.label: String
    get() = when (this) {
        DesktopFeatureState.Ready -> "Ready"
        DesktopFeatureState.InProgress -> "In progress"
        DesktopFeatureState.AndroidOnly -> "Android only"
    }

@Composable
private fun PortabilityCard(
    title: String,
    body: String,
    state: DesktopFeatureState,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusPill(
                text = state.label,
                containerColor = state.color().copy(alpha = 0.12f),
                contentColor = state.color(),
                borderColor = Color.Transparent,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

