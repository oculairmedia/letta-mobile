package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.customColors
import kotlinx.collections.immutable.ImmutableList
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAgents: () -> Unit,
    onNavigateToConversations: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (agentId: String, initialMessage: String?) -> Unit,
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
                { onNavigateToChat(agentId, null) }
            } else {
                onNavigateToAgents
            }
        }
        DashboardShortcut.SETTINGS -> onNavigateToSettings
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
                            val isPinned = shortcut in uiState.pinnedShortcuts
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
            onReorderShortcuts = viewModel::reorderShortcuts,
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
    onNavigateToChat: (String, String?) -> Unit,
    onNavigateToChatMessage: (String, String, String) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    onUnpinAgent: (String) -> Unit,
    onShortcutClick: (DashboardShortcut) -> Unit,
    onUnpinShortcut: (DashboardShortcut) -> Unit,
    onReorderShortcuts: (List<DashboardShortcut>) -> Unit,
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaSpacing.screenHorizontal)
                .padding(bottom = LettaSpacing.cardGap),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.serverUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
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
                onAgentClick = { agentId -> onNavigateToChat(agentId, null) },
                onMessageClick = { parsed ->
                    val agentId = parsed.agentId ?: return@SearchResultsContent
                    val convId = parsed.conversationId
                    val msgId = parsed.messageId
                    if (convId != null && msgId != null) {
                        onNavigateToChatMessage(agentId, convId, msgId)
                    } else {
                        onNavigateToChat(agentId, null)
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
                if (state.pinnedShortcuts.isNotEmpty()) {
                    ReorderableWidgetGrid(
                        shortcuts = state.pinnedShortcuts,
                        state = state,
                        onShortcutClick = onShortcutClick,
                        onUnpinShortcut = onUnpinShortcut,
                        onReorder = onReorderShortcuts,
                        columns = 3,
                        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
                    )
                }

                if (state.pinnedAgents.isNotEmpty()) {
                    val agentColumns = 3
                    Column(
                        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
                    ) {
                        state.pinnedAgents.chunked(agentColumns).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                            ) {
                                row.forEach { pinned ->
                                    PinnedAgentCard(
                                        name = pinned.name,
                                        onClick = { onNavigateToChat(pinned.id, null) },
                                        onUnpin = { onUnpinAgent(pinned.id) },
                                        onConfigure = { onNavigateToEditAgent(pinned.id) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(agentColumns - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(modifier = Modifier.height(LettaSpacing.cardGap))
                        }
                    }
                }
            }

            if (state.favoriteAgentId != null) {
                var homeChatText by remember { mutableStateOf("") }
                LettaInputBar(
                    text = homeChatText,
                    onTextChange = { homeChatText = it },
                    onSend = { message ->
                        onNavigateToChat(state.favoriteAgentId, message)
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedAgentCard(
    name: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val accentColors = MaterialTheme.customColors
    Card(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = accentColors.freshAccentContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                LettaIcons.Agent,
                contentDescription = null,
                tint = accentColors.freshAccent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.screen_home_pinned_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    ActionSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        title = name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_configure_agent),
            icon = LettaIcons.Edit,
            onClick = { showMenu = false; onConfigure() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_unpin_agent),
            icon = LettaIcons.PinOff,
            onClick = { showMenu = false; onUnpin() },
        )
    }
}

@Composable
private fun resolveContextualInfo(
    shortcut: DashboardShortcut,
    state: DashboardUiState,
): String? {
    return when (shortcut) {
        DashboardShortcut.AGENTS -> state.agentCount?.let {
            stringResource(R.string.widget_tile_count_format, it)
        }
        DashboardShortcut.CONVERSATIONS -> state.conversationCount?.let {
            stringResource(R.string.widget_tile_count_format, it)
        }
        DashboardShortcut.TOOLS -> state.toolCount?.let {
            stringResource(R.string.widget_tile_count_format, it)
        }
        DashboardShortcut.BLOCKS -> state.blockCount?.let {
            stringResource(R.string.widget_tile_count_format, it)
        }
        DashboardShortcut.USAGE -> state.usageSummary?.let {
            formatNumber(it.totalTokens) + " tokens"
        } ?: if (state.isUsageLoading) "Loading…" else stringResource(shortcut.descriptionResId)
        DashboardShortcut.FAVORITE_AGENT -> state.favoriteAgentName
            ?: stringResource(shortcut.descriptionResId)
        else -> {
            if (shortcut.descriptionResId != 0) {
                stringResource(shortcut.descriptionResId)
            } else null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardWidgetTile(
    shortcut: DashboardShortcut,
    contextualInfo: String?,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val accentColors = MaterialTheme.customColors

    val containerColor = accentColors.freshAccentContainer
    val contentColor = accentColors.freshAccent

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tileScale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 8f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tileElevation",
    )

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = elevation * density
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                },
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = shortcut.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (contextualInfo != null) {
                Text(
                    text = contextualInfo,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(shortcut.labelResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    ActionSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        title = stringResource(shortcut.labelResId),
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_unpin_shortcut),
            icon = LettaIcons.PinOff,
            onClick = { showMenu = false; onUnpin() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableWidgetGrid(
    shortcuts: ImmutableList<DashboardShortcut>,
    state: DashboardUiState,
    onShortcutClick: (DashboardShortcut) -> Unit,
    onUnpinShortcut: (DashboardShortcut) -> Unit,
    onReorder: (List<DashboardShortcut>) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    var currentList by remember(shortcuts) { mutableStateOf(shortcuts.toList()) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val itemRects = remember { mutableStateMapOf<Int, Rect>() }
    val haptic = LocalHapticFeedback.current

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (draggingIndex != null) available else Offset.Zero
            }
        }
    }

    val gap = LettaSpacing.cardGap

    Layout(
        content = {
            currentList.forEachIndexed { index, shortcut ->
                key(shortcut) {
                    val isDragging = draggingIndex == index

                    // Track previous slot for easing animation
                    var previousSlot by remember { mutableIntStateOf(index) }
                    val slotOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

                    LaunchedEffect(index) {
                        if (previousSlot != index && draggingIndex != index) {
                            // Item was displaced — animate from old position to new
                            val cols = columns
                            val oldCol = previousSlot % cols
                            val newCol = index % cols
                            val oldRow = previousSlot / cols
                            val newRow = index / cols

                            // We compute pixel delta using measured rects if available
                            val oldRect = itemRects[previousSlot]
                            val newRect = itemRects[index]
                            if (oldRect != null && newRect != null) {
                                val delta = Offset(
                                    oldRect.left - newRect.left,
                                    oldRect.top - newRect.top,
                                )
                                slotOffset.snapTo(delta)
                                slotOffset.animateTo(
                                    targetValue = Offset.Zero,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                        previousSlot = index
                    }

                    Box(
                        modifier = Modifier
                            .then(if (isDragging) Modifier.zIndex(10f) else Modifier)
                            .then(
                                if (isDragging) {
                                    Modifier.offset {
                                        IntOffset(
                                            dragOffset.x.roundToInt(),
                                            dragOffset.y.roundToInt(),
                                        )
                                    }
                                } else {
                                    Modifier.offset {
                                        IntOffset(
                                            slotOffset.value.x.roundToInt(),
                                            slotOffset.value.y.roundToInt(),
                                        )
                                    }
                                },
                            )
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggingIndex = index
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += Offset(dragAmount.x, dragAmount.y)

                                        val draggedRect = itemRects[index] ?: return@detectDragGesturesAfterLongPress
                                        val draggedCenter = draggedRect.center + dragOffset
                                        val targetIndex = itemRects.entries
                                            .firstOrNull { (i, rect) ->
                                                i != index && rect.contains(draggedCenter)
                                            }?.key

                                        if (targetIndex != null && targetIndex != index) {
                                            val oldRect = itemRects[index]!!
                                            val newRect = itemRects[targetIndex]!!

                                            currentList = currentList.toMutableList().apply {
                                                val item = removeAt(index)
                                                add(targetIndex, item)
                                            }
                                            draggingIndex = targetIndex
                                            dragOffset += Offset(
                                                oldRect.left - newRect.left,
                                                oldRect.top - newRect.top,
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        dragOffset = Offset.Zero
                                        onReorder(currentList)
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffset = Offset.Zero
                                        currentList = shortcuts.toList()
                                    },
                                )
                            },
                    ) {
                        DashboardWidgetTile(
                            shortcut = shortcut,
                            contextualInfo = resolveContextualInfo(shortcut, state),
                            onClick = { onShortcutClick(shortcut) },
                            onUnpin = { onUnpinShortcut(shortcut) },
                            isDragging = isDragging,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(nestedScrollConnection),
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val totalGapWidth = gapPx * (columns - 1)
        val cellWidth = (constraints.maxWidth - totalGapWidth) / columns
        val cellConstraints = constraints.copy(
            minWidth = cellWidth,
            maxWidth = cellWidth,
            minHeight = 0,
        )

        val placeables = measurables.map { it.measure(cellConstraints) }
        val rows = placeables.chunked(columns)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val totalHeight = rowHeights.sum() + gapPx * (rowHeights.size - 1).coerceAtLeast(0)

        layout(constraints.maxWidth, totalHeight) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEachIndexed { colIndex, placeable ->
                    val globalIndex = rowIndex * columns + colIndex
                    itemRects[globalIndex] = Rect(
                        Offset(x.toFloat(), y.toFloat()),
                        Size(cellWidth.toFloat(), rowHeights[rowIndex].toFloat()),
                    )
                    placeable.placeRelative(x, y)
                    x += cellWidth + gapPx
                }
                y += rowHeights[rowIndex] + gapPx
            }
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onToggle)
            .padding(top = if (topPadding) 8.dp else 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SearchResultsContent(
    agentResults: List<Agent>,
    messageResults: List<ParsedSearchMessage>,
    toolResults: List<Tool>,
    blockResults: List<Block>,
    isSearching: Boolean,
    searchQuery: String,
    onAgentClick: (String) -> Unit,
    onMessageClick: (ParsedSearchMessage) -> Unit,
    onToolClick: (String) -> Unit,
    onBlockClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val highlightTextColor = MaterialTheme.colorScheme.primary

    var agentsExpanded by rememberSaveable { mutableStateOf(true) }
    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    var blocksExpanded by rememberSaveable { mutableStateOf(true) }
    var messagesExpanded by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agentResults.isNotEmpty()) {
            item(key = "agents-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_agents_section),
                    count = agentResults.size,
                    expanded = agentsExpanded,
                    onToggle = { agentsExpanded = !agentsExpanded },
                )
            }
            if (agentsExpanded) {
                items(agentResults, key = { "agent-${it.id}" }) { agent ->
                    Card(
                        onClick = { onAgentClick(agent.id) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                LettaIcons.Agent,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = highlightMatches(agent.name, searchQuery, highlightColor, highlightTextColor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                agent.description?.let { desc ->
                                    Text(
                                        text = highlightMatches(desc, searchQuery, highlightColor, highlightTextColor),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (toolResults.isNotEmpty()) {
            item(key = "tools-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_tools_section),
                    count = toolResults.size,
                    expanded = toolsExpanded,
                    onToggle = { toolsExpanded = !toolsExpanded },
                    topPadding = true,
                )
            }
            if (toolsExpanded) {
                items(toolResults, key = { "tool-${it.id}" }) { tool ->
                    Card(
                        onClick = { onToolClick(tool.id) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                LettaIcons.Tool,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = highlightMatches(tool.name, searchQuery, highlightColor, highlightTextColor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                tool.description?.let { desc ->
                                    Text(
                                        text = highlightMatches(desc, searchQuery, highlightColor, highlightTextColor),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (blockResults.isNotEmpty()) {
            item(key = "blocks-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_blocks_section),
                    count = blockResults.size,
                    expanded = blocksExpanded,
                    onToggle = { blocksExpanded = !blocksExpanded },
                    topPadding = true,
                )
            }
            if (blocksExpanded) {
                items(blockResults, key = { "block-${it.id}" }) { block ->
                    Card(
                        onClick = { onBlockClick(block.id) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                LettaIcons.ViewModule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = highlightMatches(block.label ?: "Unnamed", searchQuery, highlightColor, highlightTextColor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                block.description?.let { desc ->
                                    Text(
                                        text = highlightMatches(desc, searchQuery, highlightColor, highlightTextColor),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (messageResults.isNotEmpty()) {
            item(key = "messages-header") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.screen_home_search_messages_section),
                    count = messageResults.size,
                    expanded = messagesExpanded,
                    onToggle = { messagesExpanded = !messagesExpanded },
                    topPadding = true,
                )
            }
            if (messagesExpanded) {
                items(messageResults.size, key = { "msg-$it" }) { index ->
                    val msg = messageResults[index]
                    Card(
                        onClick = { onMessageClick(msg) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    LettaIcons.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = msg.role?.replaceFirstChar { it.uppercase() } ?: "Message",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = highlightMatches(msg.content ?: "", searchQuery, highlightColor, highlightTextColor),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (isSearching && messageResults.isEmpty()) {
            item(key = "messages-loading") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.screen_home_search_messages_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (!isSearching && agentResults.isEmpty() && messageResults.isEmpty() && toolResults.isEmpty() && blockResults.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.screen_home_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

private fun highlightMatches(
    text: String,
    query: String,
    highlightColor: Color,
    matchTextColor: Color = Color.Unspecified,
) = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.trim().lowercase()
    var cursor = 0
    var matched = false
    while (cursor < text.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, cursor)
        if (matchIndex < 0) {
            append(text.substring(cursor))
            break
        }
        matched = true
        append(text.substring(cursor, matchIndex))
        withStyle(
            SpanStyle(
                background = highlightColor,
                fontWeight = FontWeight.Bold,
                color = if (matchTextColor != Color.Unspecified) matchTextColor else Color.Unspecified,
            )
        ) {
            append(text.substring(matchIndex, matchIndex + lowerQuery.length))
        }
        cursor = matchIndex + lowerQuery.length
    }
}

private fun formatNumber(value: Int): String = String.format(Locale.US, "%,d", value)
