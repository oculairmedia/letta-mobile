package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurface
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
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
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

private const val DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS = 30_000L

@Composable
fun LettaDesktopApp() {
    var selectedDestination by rememberSaveable { mutableStateOf(DesktopDestination.Conversations) }
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
            agentNamesByIdProvider = {
                val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
                agentRepository.refreshAgentsIfStale(maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS)
                agentRepository.agents.value.associate { agent -> agent.id.value to agent.name }
            },
        )
    }
    val chatState by chatController.state.collectAsState()
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

    DesktopMaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (selectedDestination == DesktopDestination.Conversations) {
                DesktopChatSurface(
                    state = chatState,
                    onConversationSelected = chatController::selectConversation,
                    onDeleteConversation = chatController::deleteConversation,
                    onComposerTextChanged = chatController::updateComposerText,
                    onSend = chatController::send,
                    onAttachImage = { pickerLauncher.launch() },
                    onRemoveImageAttachment = chatController::removeImageAttachment,
                    onRetryConnection = chatController::retryConnection,
                    onSettingsSelected = { selectedDestination = DesktopDestination.Settings },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(Modifier.fillMaxSize()) {
                    DesktopNavigation(
                        selectedDestination = selectedDestination,
                        onDestinationSelected = { selectedDestination = it },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                    )
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
                        onChatAttachImage = {
                            pickerLauncher.launch()
                        },
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
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNavigation(
    selectedDestination: DesktopDestination,
    onDestinationSelected: (DesktopDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column {
                Text(
                    text = "Letta Desktop",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Workspace",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DesktopNavRow(
            label = "New session",
            icon = Icons.Outlined.Add,
            selected = false,
            onClick = { onDestinationSelected(DesktopDestination.Conversations) },
        )
        DesktopNavRow(
            label = "Search sessions...",
            icon = Icons.Outlined.Search,
            selected = false,
            subdued = true,
            onClick = { onDestinationSelected(DesktopDestination.Conversations) },
        )

        SidebarSection("Workspace")
        DesktopNavRow(
            label = "Messaging",
            icon = DesktopDestination.Conversations.icon,
            selected = selectedDestination == DesktopDestination.Conversations,
            onClick = { onDestinationSelected(DesktopDestination.Conversations) },
        )
        DesktopNavRow(
            label = "Memory",
            icon = DesktopDestination.Memory.icon,
            selected = selectedDestination == DesktopDestination.Memory,
            onClick = { onDestinationSelected(DesktopDestination.Memory) },
        )
        DesktopNavRow(
            label = "Schedules",
            icon = DesktopDestination.Schedules.icon,
            selected = selectedDestination == DesktopDestination.Schedules,
            onClick = { onDestinationSelected(DesktopDestination.Schedules) },
        )
        DesktopNavRow(
            label = "Channels",
            icon = DesktopDestination.Channels.icon,
            selected = selectedDestination == DesktopDestination.Channels,
            onClick = { onDestinationSelected(DesktopDestination.Channels) },
        )
        DesktopNavRow(
            label = "Skills & Tools",
            icon = DesktopDestination.Agents.icon,
            selected = selectedDestination == DesktopDestination.Agents,
            onClick = { onDestinationSelected(DesktopDestination.Agents) },
        )
        DesktopNavRow(
            label = "Artifacts",
            icon = DesktopDestination.Overview.icon,
            selected = selectedDestination == DesktopDestination.Overview,
            onClick = { onDestinationSelected(DesktopDestination.Overview) },
        )

        Spacer(Modifier.weight(1f))

        DesktopNavRow(
            label = "Settings",
            icon = DesktopDestination.Settings.icon,
            selected = selectedDestination == DesktopDestination.Settings,
            tooltip = "Settings",
            onClick = { onDestinationSelected(DesktopDestination.Settings) },
        )

        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small),
                )
                Text(
                    text = "Gateway ready",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    modifier: Modifier = Modifier,
) {
    if (destination == DesktopDestination.Conversations) {
        DesktopChatSurface(
            state = chatState,
            onConversationSelected = onChatConversationSelected,
            onDeleteConversation = onChatConversationDeleted,
            onComposerTextChanged = onChatComposerTextChanged,
            onSend = onChatSend,
            onAttachImage = onChatAttachImage,
            onRemoveImageAttachment = onChatRemoveImageAttachment,
            onRetryConnection = onChatRetryConnection,
            modifier = modifier,
        )
        return
    }
    if (destination == DesktopDestination.Memory) {
        DesktopMemorySurface(
            state = memoryState,
            onRefresh = onMemoryRefresh,
            onAgentSelected = onMemoryAgentSelected,
            modifier = modifier,
        )
        return
    }
    if (destination == DesktopDestination.Schedules) {
        DesktopScheduleLibrarySurface(
            state = scheduleLibraryState,
            onRefresh = onSchedulesRefresh,
            onAgentSelected = onScheduleAgentSelected,
            modifier = modifier,
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

