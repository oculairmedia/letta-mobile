package com.letta.mobile.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.agents.AgentItemState
import com.letta.mobile.ui.agents.AgentRail
import com.letta.mobile.ui.settings.BackendSettingsCard
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.fs.WebWorkspaceController
import com.letta.mobile.web.iroh.IrohWasmBridge
import kotlinx.coroutines.launch

data class WebChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: String,
)

enum class WebNavDestination {
    CHAT,
    SETTINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LettaWebApp() {
    val coroutineScope = rememberCoroutineScope()
    val workspaceController = remember { WebWorkspaceController() }
    val appServerGateway = remember { WasmAppServerClientGateway(coroutineScope) }
    var selectedWorkspaceName by remember { mutableStateOf<String?>(null) }

    // Navigation & Sidebar State
    var currentDestination by remember { mutableStateOf(WebNavDestination.CHAT) }
    var showAgentSidebar by remember { mutableStateOf(true) }

    // Backend Config (Desktop Pattern)
    var config by remember {
        mutableStateOf(
            LettaConfig(
                id = "default",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
                accessToken = null,
            )
        )
    }

    // Parse & Validate Iroh Ticket
    val parsedTicket = remember(config.serverUrl) {
        IrohWasmBridge.parseTicket(config.serverUrl)
    }

    // Live Agents State
    val agents = remember {
        mutableStateListOf(
            AgentItemState(
                id = "agent-1",
                name = "Nora",
                description = "Primary engineering & coding assistant",
                model = "letta/letta-free",
                isOnline = true,
            ),
            AgentItemState(
                id = "agent-2",
                name = "Claude Sonnet",
                description = "Deep reasoning & architecture specialist",
                model = "anthropic/claude-3-7-sonnet",
                isOnline = true,
            ),
        )
    }
    var selectedAgent by remember { mutableStateOf(agents[0]) }
    var isLoadingAgents by remember { mutableStateOf(false) }
    var agentLoadError by remember { mutableStateOf<String?>(null) }

    // Function to reload agents from the connected Iroh AppServer
    val loadAgents: () -> Unit = {
        coroutineScope.launch {
            isLoadingAgents = true
            agentLoadError = null
            val result = appServerGateway.listAgents(config)
            result.onSuccess { fetched ->
                if (fetched.isNotEmpty()) {
                    agents.clear()
                    agents.addAll(fetched)
                    if (agents.none { it.id == selectedAgent.id }) {
                        selectedAgent = agents.first()
                    }
                }
            }.onFailure { err ->
                println("Failed to fetch live agents from AppServer: ${err.message}")
                agentLoadError = err.message ?: "Connection failed"
            }
            isLoadingAgents = false
        }
    }

    // Initial agent load on startup
    LaunchedEffect(config.serverUrl) {
        loadAgents()
    }

    val isConnected = remember(config.serverUrl) { config.serverUrl.isNotBlank() }

    var inputMessage by remember { mutableStateOf("") }
    val messages = remember(selectedAgent.id) {
        mutableStateListOf(
            WebChatMessage(
                id = "1",
                sender = selectedAgent.name,
                text = "Hello! I am ${selectedAgent.name} (${selectedAgent.model}). I am connected to Iroh Node ${parsedTicket.nodeId.take(12)}... via the AppServer P2P protocol. Pick a local workspace directory below to begin direct file inspection.",
                isUser = false,
                timestamp = "Just now",
            )
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredAgents = remember(searchQuery, agents.toList()) {
        if (searchQuery.isBlank()) agents
        else agents.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                (it.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    SharedMaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 1. Leftmost Icon Rail (Desktop Pattern)
                Column(
                    modifier = Modifier
                        .width(68.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        AgentRail(
                            agents = agents,
                            selectedAgentId = selectedAgent.id,
                            onAgentSelected = { agent ->
                                selectedAgent = agent
                                currentDestination = WebNavDestination.CHAT
                            },
                            onAddAgent = {
                                val newIndex = agents.size + 1
                                val newAgent = AgentItemState(
                                    id = "agent-$newIndex",
                                    name = "Agent $newIndex",
                                    description = "Custom assistant",
                                    model = "letta/letta-free",
                                )
                                agents.add(newAgent)
                                selectedAgent = newAgent
                                currentDestination = WebNavDestination.CHAT
                            },
                        )
                    }

                    // Bottom Rail Actions: Sidebar Toggle, Local Workspace, Settings
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = { showAgentSidebar = !showAgentSidebar }
                        ) {
                            Icon(
                                imageVector = if (showAgentSidebar) Icons.Default.MenuOpen else Icons.Default.Menu,
                                contentDescription = "Toggle Sidebar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val success = workspaceController.openWorkspaceDirectory()
                                    if (success) {
                                        selectedWorkspaceName = workspaceController.workspaceName
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Open Workspace",
                                tint = if (selectedWorkspaceName != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = { currentDestination = WebNavDestination.SETTINGS }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Backend Settings",
                                tint = if (currentDestination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 2. Expandable Agent List Sidebar (Desktop Pattern)
                if (showAgentSidebar) {
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Agents (${agents.size})",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (isLoadingAgents) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = loadAgents,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh Agents",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Iroh Node Status Pill
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (parsedTicket.publicKeyValid) MaterialTheme.customColors.onlineColor else MaterialTheme.customColors.reconnectingColor
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Iroh: ${parsedTicket.nodeId.take(8)}... (${if (parsedTicket.publicKeyValid) "Valid Ed25519" else "Invalid Key"})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Error/Status banner if backend load had issues
                            if (agentLoadError != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = "Iroh AppServer Status:",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            text = "Iroh node '${parsedTicket.nodeId.take(12)}...' is configured. If direct endpoint is not yet connected, check your token in Settings.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Configure in Settings →",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { currentDestination = WebNavDestination.SETTINGS },
                                        )
                                    }
                                }
                            }

                            // Search Field
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        "Search agents...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            )

                            // Agent List
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(filteredAgents) { agent ->
                                    val isSelected = agent.id == selectedAgent.id && currentDestination == WebNavDestination.CHAT
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedAgent = agent
                                                currentDestination = WebNavDestination.CHAT
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = agent.name.take(2).uppercase(),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(10.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = agent.name,
                                                    style = MaterialTheme.typography.labelLarge.copy(
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = agent.model,
                                                    style = MaterialTheme.typography.labelSmall,
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

                        // Bottom Sidebar Nav Row: Settings (Desktop Pattern)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (currentDestination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (currentDestination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.outline else Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { currentDestination = WebNavDestination.SETTINGS },
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    tint = if (currentDestination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Settings",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (currentDestination == WebNavDestination.SETTINGS) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    color = if (currentDestination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                // 3. Main Content Pane: Chat vs Settings Page
                when (currentDestination) {
                    WebNavDestination.CHAT -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            // Top App Bar Header
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${selectedAgent.name} (${selectedAgent.model})",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (isConnected) MaterialTheme.customColors.onlineColor else MaterialTheme.customColors.reconnectingColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isConnected) {
                                                "Iroh: ${parsedTicket.nodeId.take(8)}..."
                                            } else "Disconnected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Workspace Status Pill
                                    AssistChip(
                                        onClick = {
                                            coroutineScope.launch {
                                                val success = workspaceController.openWorkspaceDirectory()
                                                if (success) {
                                                    selectedWorkspaceName = workspaceController.workspaceName
                                                }
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = selectedWorkspaceName?.let { "Workspace: $it" } ?: "Select Local Workspace",
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                            }

                            // Chat Messages List
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(messages) { msg ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (msg.isUser) MaterialTheme.customColors.userBubbleBgColor else MaterialTheme.customColors.agentBubbleBgColor,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                modifier = Modifier.widthIn(max = 640.dp),
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Text(
                                                        text = msg.sender,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.customColors.agentAColor,
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = msg.text,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Chat Composer Bottom Bar
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = inputMessage,
                                        onValueChange = { inputMessage = it },
                                        placeholder = { Text("Message ${selectedAgent.name}...") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = false,
                                        maxLines = 4,
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    IconButton(
                                        onClick = {
                                            if (inputMessage.isNotBlank()) {
                                                val userText = inputMessage
                                                inputMessage = ""
                                                messages.add(
                                                    WebChatMessage(
                                                        id = (messages.size + 1).toString(),
                                                        sender = "You",
                                                        text = userText,
                                                        isUser = true,
                                                        timestamp = "Just now",
                                                    )
                                                )

                                                coroutineScope.launch {
                                                    val wsName = selectedWorkspaceName ?: "no workspace selected"
                                                    messages.add(
                                                        WebChatMessage(
                                                            id = (messages.size + 1).toString(),
                                                            sender = selectedAgent.name,
                                                            text = "Received: \"$userText\". Connected to Iroh Node ${parsedTicket.nodeId.take(12)}... Active local workspace: '$wsName'.",
                                                            isUser = false,
                                                            timestamp = "Just now",
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    WebNavDestination.SETTINGS -> {
                        // Desktop-identical Settings Destination
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(32.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Settings",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Button(
                                    onClick = { currentDestination = WebNavDestination.CHAT },
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Back to Chat")
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Canonical Desktop Backend Card
                            BackendSettingsCard(
                                config = config,
                                onConfigSaved = { newConfig ->
                                    config = newConfig
                                    loadAgents()
                                },
                                onTokenCleared = {
                                    config = config.copy(accessToken = null)
                                    loadAgents()
                                },
                                onIrohIdentityReset = {
                                    loadAgents()
                                },
                                modifier = Modifier.widthIn(max = 760.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
