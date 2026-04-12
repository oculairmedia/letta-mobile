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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.NavigationDrawerItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.R
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.util.formatRelativeTime
import com.letta.mobile.ui.components.ChatBackgroundPicker
import com.letta.mobile.ui.components.ConnectionState
import com.letta.mobile.ui.components.ConnectionStatusBanner
import com.letta.mobile.ui.theme.ChatBackground

import com.letta.mobile.util.ConnectivityMonitor
import com.letta.mobile.ui.navigation.agentAvatarSharedElementKey
import com.letta.mobile.ui.navigation.optionalSharedElement
import kotlinx.coroutines.launch
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScaffold(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: ((String) -> Unit)? = null,
    onNavigateToTools: (() -> Unit)? = null,
    onSwitchConversation: ((String, String) -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConversationPicker by remember { mutableStateOf(false) }
    val chatBackground by viewModel.chatBackground.collectAsStateWithLifecycle()

    val agentName = uiState.agentName
    val agentId = viewModel.agentId
    val conversationId = viewModel.conversationId
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                    messageCount = uiState.messages.size,
                    chatBackground = chatBackground,
                    onChatBackgroundChange = { viewModel.setChatBackground(it) },
                    onEditAgent = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings(agentId)
                    },
                    onArchivalMemory = {
                        scope.launch { drawerState.close() }
                        onNavigateToArchival?.invoke(agentId)
                    },
                    onTools = {
                        scope.launch { drawerState.close() }
                        onNavigateToTools?.invoke()
                    },
                    onResetMessages = {
                        scope.launch { drawerState.close() }
                        viewModel.resetMessages()
                    },
                    onClose = { scope.launch { drawerState.close() } },
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(
                            text = agentName.ifBlank { stringResource(R.string.screen_chat_title) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = LettaTopBarDefaults.largeTopAppBarColors(),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(LettaIcons.Menu, "Menu")
                        }
                    }
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                AgentConversationHeader(
                    agentId = agentId,
                    agentName = agentName,
                    conversationId = conversationId,
                    onClick = { showConversationPicker = true },
                )
                ChatScreen(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    chatBackground = chatBackground,
                )
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

@Composable
private fun AgentConversationHeader(
    agentId: String,
    agentName: String,
    conversationId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                LettaIcons.Agent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(LettaIconSizing.Toolbar)
                    .optionalSharedElement(agentAvatarSharedElementKey(agentId)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agentName.ifBlank { stringResource(R.string.screen_chat_title) },
                    style = MaterialTheme.typography.titleMedium,
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
                        LettaIcons.ArrowDropDown,
                        contentDescription = "Switch conversation",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
                Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar))
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
                                val timeText = formatRelativeTime(conversation.lastMessageAt ?: conversation.createdAt)
                                if (timeText.isNotBlank()) {
                                    Text(
                                        text = timeText,
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
    chatBackground: ChatBackground,
    onChatBackgroundChange: (ChatBackground) -> Unit,
    onEditAgent: () -> Unit,
    onArchivalMemory: () -> Unit,
    onTools: () -> Unit = {},
    onResetMessages: () -> Unit = {},
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
                LettaIcons.Agent,
                contentDescription = "Agent",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = agentName.ifBlank { "Agent" },
                style = MaterialTheme.typography.titleLarge,
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
            icon = { Icon(LettaIcons.Edit, contentDescription = "Edit") },
            label = { Text(stringResource(R.string.screen_drawer_edit_agent)) },
            selected = false,
            onClick = onEditAgent,
        )

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Inventory, contentDescription = "Archival") },
            label = { Text(stringResource(R.string.screen_drawer_archival)) },
            selected = false,
            onClick = onArchivalMemory,
        )

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Tool, contentDescription = "Tools") },
            label = { Text(stringResource(R.string.common_tools)) },
            selected = false,
            onClick = onTools,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            icon = { Icon(LettaIcons.Delete, contentDescription = "Reset") },
            label = { Text("Reset Messages") },
            selected = false,
            onClick = onResetMessages,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        ChatBackgroundPicker(
            selected = chatBackground,
            onSelect = onChatBackgroundChange,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = agentId.take(12) + "\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
