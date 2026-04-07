package com.letta.mobile.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.R
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.components.ConnectionState
import com.letta.mobile.ui.components.ConnectionStatusBanner
import com.letta.mobile.ui.screens.settings.AgentSettingsScreen
import com.letta.mobile.ui.screens.tools.ToolsScreen
import com.letta.mobile.util.ConnectivityMonitor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScaffold(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onSwitchConversation: ((String, String) -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConversationPicker by remember { mutableStateOf(false) }

    val agentName = (uiState as? UiState.Success)?.data?.agentName ?: ""
    val agentId = viewModel.agentId
    val conversationId = viewModel.conversationId
    val connectivityMonitorAvailable = false
    val connectionState = ConnectionState.Online

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    agentName = agentName,
                    agentId = agentId,
                    messageCount = (uiState as? UiState.Success)?.data?.messages?.size ?: 0,
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings(agentId)
                    },
                    onArchivalMemory = {
                        scope.launch { drawerState.close() }
                        onNavigateToArchival?.invoke(agentId)
                    },
                    onClose = { scope.launch { drawerState.close() } },
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier.clickable { showConversationPicker = true }
                        ) {
                            Text(
                                text = agentName.ifBlank { stringResource(R.string.screen_chat_title) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (conversationId != null) "Conversation" else "Default",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch conversation",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Chat, stringResource(R.string.common_chat)) },
                        label = { Text(stringResource(R.string.common_chat)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Build, stringResource(R.string.common_tools)) },
                        label = { Text(stringResource(R.string.common_tools)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, stringResource(R.string.common_settings)) },
                        label = { Text(stringResource(R.string.common_settings)) }
                    )
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                ConnectionStatusBanner(
                    state = if (connectivityMonitorAvailable) connectionState else ConnectionState.Online,
                )
                when (selectedTab) {
                    0 -> ChatScreen(modifier = Modifier.weight(1f))
                    1 -> ToolsScreen(modifier = Modifier.weight(1f))
                    2 -> AgentSettingsScreen(
                        onNavigateBack = { selectedTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showConversationPicker) {
        ConversationPickerSheet(
            agentId = agentId,
            currentConversationId = conversationId,
            onDismiss = { showConversationPicker = false },
            onConversationSelected = { convId ->
                showConversationPicker = false
                onSwitchConversation?.invoke(agentId, convId)
            },
            onNewConversation = {
                showConversationPicker = false
                onSwitchConversation?.invoke(agentId, "")
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationPickerSheet(
    agentId: String,
    currentConversationId: String?,
    onDismiss: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
) {
    val conversationRepo: ConversationRepository = hiltViewModel<ConversationPickerViewModel>().conversationRepository
    val conversations by conversationRepo.getConversations(agentId).collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(agentId) {
        conversationRepo.refreshConversations(agentId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.common_conversations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedButton(
                onClick = onNewConversation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screen_conversations_new_action))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (conversations.isEmpty()) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val isSelected = conversation.id == currentConversationId
                        Card(
                            onClick = { onConversationSelected(conversation.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isSelected) CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) else CardDefaults.cardColors(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = conversation.summary ?: "Conversation",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                conversation.lastMessageAt?.let { time ->
                                    Text(
                                        text = formatRelativeTime(time),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@HiltViewModel
class ConversationPickerViewModel @Inject constructor(
    val conversationRepository: ConversationRepository,
) : ViewModel()

@Composable
private fun DrawerContent(
    agentName: String,
    agentId: String,
    messageCount: Int,
    onEditAgent: () -> Unit,
    onArchivalMemory: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = "Agent",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = agentName.ifBlank { "Agent" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$messageCount messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Edit, contentDescription = "Edit") },
            label = { Text(stringResource(R.string.screen_drawer_edit_agent)) },
            selected = false,
            onClick = onEditAgent,
        )

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Inventory2, contentDescription = "Archival") },
            label = { Text(stringResource(R.string.screen_drawer_archival)) },
            selected = false,
            onClick = onArchivalMemory,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = agentId.take(12) + "\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
