package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.key
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.letta.mobile.R
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.customColors
import androidx.compose.material3.IconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (agentId: String, agentName: String?, initialMessage: String?) -> Unit,
    onNavigateToChatMessage: (agentId: String, conversationId: String, messageId: String) -> Unit,
    onNavigateToEditAgent: (agentId: String) -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToTemplates: () -> Unit = {},
    onNavigateToArchives: () -> Unit = {},
    onNavigateToFolders: () -> Unit = {},
    onNavigateToGroups: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToIdentities: () -> Unit = {},
    onNavigateToSchedules: () -> Unit = {},
    onNavigateToRuns: () -> Unit = {},
    onNavigateToJobs: () -> Unit = {},
    onNavigateToMessageBatches: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToTelemetry: () -> Unit = {},
    onNavigateToSystemAccess: () -> Unit = {},
    onNavigateToBotSettings: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    activeBackendLabel: String? = null,
    onNavigateToBackendSwitcher: (() -> Unit)? = null,
    title: String = "Letta",
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun shortcutNavigator(shortcut: DashboardShortcut): () -> Unit = when (shortcut) {
        DashboardShortcut.CONVERSATIONS -> onNavigateToConversations
        DashboardShortcut.AGENTS -> onNavigateToAgents
        DashboardShortcut.TOOLS -> onNavigateToTools
        DashboardShortcut.BLOCKS -> onNavigateToBlocks
        DashboardShortcut.TEMPLATES -> onNavigateToTemplates
        DashboardShortcut.ARCHIVES -> onNavigateToArchives
        DashboardShortcut.FOLDERS -> onNavigateToFolders
        DashboardShortcut.GROUPS -> onNavigateToGroups
        DashboardShortcut.PROVIDERS -> onNavigateToProviders
        DashboardShortcut.IDENTITIES -> onNavigateToIdentities
        DashboardShortcut.SCHEDULES -> onNavigateToSchedules
        DashboardShortcut.RUNS -> onNavigateToRuns
        DashboardShortcut.JOBS -> onNavigateToJobs
        DashboardShortcut.MESSAGE_BATCHES -> onNavigateToMessageBatches
        DashboardShortcut.MCP_SERVERS -> onNavigateToMcp
        DashboardShortcut.BOT_SETTINGS -> onNavigateToBotSettings
        DashboardShortcut.PROJECTS -> onNavigateToProjects
        DashboardShortcut.MODELS -> onNavigateToModels
        DashboardShortcut.USAGE -> onNavigateToUsage
        DashboardShortcut.FAVORITE_AGENT -> {
            val agentId = uiState.favoriteAgentId
            if (agentId != null) {
                { onNavigateToChat(agentId, uiState.favoriteAgentName, null) }
            } else {
                onNavigateToAgents
            }
        }
        DashboardShortcut.SETTINGS -> onNavigateToSettings
        DashboardShortcut.TELEMETRY -> onNavigateToTelemetry
        DashboardShortcut.SYSTEM_ACCESS -> onNavigateToSystemAccess
        DashboardShortcut.ABOUT -> onNavigateToAbout
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Letta",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    )

                    var previousGroup: DashboardShortcut.Group? = null
                    DashboardShortcut.entries.forEach { shortcut ->
                        if (previousGroup != null && shortcut.group != previousGroup) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 28.dp),
                            )
                        }
                        previousGroup = shortcut.group

                        key(shortcut) {
                            val isPinned = uiState.pinnedItems.any {
                                it is PinnedItem.Shortcut && it.value == shortcut
                            }
                            val context = LocalContext.current
                            val label = stringResource(shortcut.labelResId)

                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .combinedClickable(
                                        onClick = {
                                            scope.launch { drawerState.close() }
                                            shortcutNavigator(shortcut)()
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (isPinned) {
                                                viewModel.unpinShortcut(shortcut)
                                                android.widget.Toast
                                                    .makeText(context, "$label unpinned", android.widget.Toast.LENGTH_SHORT)
                                                    .show()
                                            } else {
                                                viewModel.pinShortcut(shortcut)
                                                android.widget.Toast
                                                    .makeText(context, "$label pinned", android.widget.Toast.LENGTH_SHORT)
                                                    .show()
                                            }
                                        },
                                    )
                                    .padding(start = 16.dp, end = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    shortcut.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = viewModel::clearSearch,
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_home_search_placeholder),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title)
                                if (uiState.isConnected) {
                                    Icon(
                                        LettaIcons.Circle,
                                        contentDescription = "Connected",
                                        tint = MaterialTheme.customColors.onlineColor,
                                        modifier = Modifier.size(8.dp),
                                    )
                                }
                                if (activeBackendLabel != null && onNavigateToBackendSwitcher != null) {
                                    AssistChip(
                                        onClick = onNavigateToBackendSwitcher,
                                        label = { Text(activeBackendLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(LettaIcons.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(LettaIcons.Settings, contentDescription = "Settings")
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
            ExpandableSearchField(
                query = uiState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                onClear = viewModel::clearSearch,
                expanded = isSearchExpanded,
                placeholder = stringResource(R.string.screen_home_search_placeholder),
            )
            }
        },
    ) { paddingValues ->
        HomeContent(
            state = uiState,
            onNavigateToTools = onNavigateToTools,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToChat = onNavigateToChat,
            onNavigateToChatMessage = onNavigateToChatMessage,
            onNavigateToEditAgent = onNavigateToEditAgent,
            onUnpinAgent = viewModel::unpinAgent,
            onShortcutClick = { shortcut -> shortcutNavigator(shortcut)() },
            onUnpinShortcut = viewModel::unpinShortcut,
            onReorderPinnedItems = viewModel::reorderPinnedItems,
            modifier = Modifier.padding(paddingValues),
        )
    }
    }
}

@Composable
private fun HomeContent(
    state: DashboardUiState,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToChat: (String, String?, String?) -> Unit,
    onNavigateToChatMessage: (String, String, String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    onUnpinAgent: (String) -> Unit,
    onShortcutClick: (DashboardShortcut) -> Unit,
    onUnpinShortcut: (DashboardShortcut) -> Unit,
    onReorderPinnedItems: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        state.error?.let { error ->
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LettaSpacing.screenHorizontal)
                    .padding(bottom = LettaSpacing.cardGap),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (state.isSearchActive) {
            SearchResultsContent(
                agentResults = state.agentResults,
                messageResults = state.messageResults,
                toolResults = state.toolResults,
                blockResults = state.blockResults,
                isSearching = state.isSearching,
                searchQuery = state.searchQuery,
                onAgentClick = { agent -> onNavigateToChat(agent.id.value, agent.name, null) },
                onMessageClick = { parsed ->
                    val agentId = parsed.agentId ?: return@SearchResultsContent
                    val convId = parsed.conversationId
                    val msgId = parsed.messageId
                    if (convId != null && msgId != null) {
                        onNavigateToChatMessage(agentId, convId, msgId)
                    } else {
                        onNavigateToChat(agentId, null, null)
                    }
                },
                onToolClick = { onNavigateToTools() },
                onBlockClick = { onNavigateToBlocks() },
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
            ) {
                if (state.isPinnedItemsLoading) {
                    Column(
                        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                    ) {
                        for (row in 0..2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                            ) {
                                for (col in 0..2) {
                                    ShimmerBox(
                                        modifier = Modifier.weight(1f),
                                        height = 100.dp,
                                    )
                                }
                            }
                        }
                    }
                } else if (state.pinnedItems.isNotEmpty()) {
                    ReorderablePinnedItemsGrid(
                        items = state.pinnedItems,
                        state = state,
                        onShortcutClick = onShortcutClick,
                        onUnpinShortcut = onUnpinShortcut,
                        onAgentClick = { onNavigateToChat(it.id, it.name, null) },
                        onUnpinAgent = { onUnpinAgent(it.id) },
                        onConfigureAgent = { onNavigateToEditAgent(it.id) },
                        onReorder = onReorderPinnedItems,
                        columns = 3,
                        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
                    )
                }
            }

            if (state.favoriteAgentId != null) {
                var homeChatText by remember { mutableStateOf("") }
                LettaInputBar(
                    text = homeChatText,
                    onTextChange = { homeChatText = it },
                    onSend = { message ->
                        onNavigateToChat(state.favoriteAgentId, state.favoriteAgentName, message)
                        homeChatText = ""
                    },
                    placeholder = stringResource(R.string.screen_home_chat_placeholder),
                    sendContentDescription = stringResource(R.string.action_send_message),
                    maxLines = 1,
                )
            }
        }
    }
}
