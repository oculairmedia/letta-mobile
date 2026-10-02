package com.letta.mobile.web

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.web.chat.WebChatOpener
import com.letta.mobile.web.chat.rememberWebChatLoad
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebSettingsStore
import com.letta.mobile.web.fs.WebWorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class WebNavDestination { CHAT, SETTINGS }

@Composable
fun LettaWebApp() {
    val scope = rememberCoroutineScope()
    val workspace = remember { WebWorkspaceController() }
    val gateway = remember { WasmAppServerClientGateway(scope) }
    val settingsStore = remember { WebSettingsStore() }
    val savedSettings = remember(settingsStore) { settingsStore.load() }
    // letta-mobile-o4ygk.4.5: boards live in memory for the tab's lifetime (no web persistence yet).
    val canvasStore = remember { InMemoryCanvasDocumentStore() }
    val connectionState by gateway.state.collectAsState()
    val agents = remember { mutableStateListOf<AgentItemState>() }
    var config by remember { mutableStateOf(savedSettings.config) }
    var destination by remember { mutableStateOf(WebNavDestination.CHAT) }
    var showSidebar by remember { mutableStateOf(true) }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var isLoadingAgents by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<String?>(null) }
    var refreshSequence by remember { mutableIntStateOf(0) }
    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
    val chat = rememberWebChatLoad(remember(gateway) { WebChatOpener(gateway::openChat) }, selectedAgent, connectionState)
    LaunchedEffect(config.serverUrl, config.accessToken, config.mode, refreshSequence) {
        isLoadingAgents = true
        uiError = null
        try {
            val fetched = gateway.listAgents(config)
            agents.clear()
            agents.addAll(fetched)
            selectedAgentId = selectedAgentId?.takeIf { id -> fetched.any { it.id == id } }
                ?: fetched.firstOrNull()?.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            agents.clear()
            selectedAgentId = null
            uiError = error.message ?: "Connection failed"
        } finally {
            isLoadingAgents = false
        }
    }
    fun selectAgent(agent: AgentItemState) {
        selectedAgentId = agent.id
        destination = WebNavDestination.CHAT
    }
    val openWorkspace: () -> Unit = {
        scope.launch { if (workspace.openWorkspaceDirectory()) selectedWorkspace = workspace.workspaceName }
    }
    SharedMaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val compact = maxWidth < CompactWidth
                Row(modifier = Modifier.fillMaxSize()) {
                    if (!compact) {
                        WebNavigationRail(
                            agents = agents,
                            selectedAgentId = selectedAgentId,
                            destination = destination,
                            showSidebar = showSidebar,
                            workspaceSelected = selectedWorkspace != null,
                            onAgentSelected = ::selectAgent,
                            onToggleSidebar = { showSidebar = !showSidebar },
                            onOpenWorkspace = openWorkspace,
                            onSettings = { destination = WebNavDestination.SETTINGS },
                        )
                        if (showSidebar) {
                            WebAgentSidebar(
                                agents = agents,
                                selectedAgentId = selectedAgentId,
                                connectionState = connectionState,
                                isLoading = isLoadingAgents,
                                error = uiError,
                                onAgentSelected = ::selectAgent,
                                onRefresh = { refreshSequence += 1 },
                                onSettings = { destination = WebNavDestination.SETTINGS },
                            )
                        }
                    }
                    when (destination) {
                        WebNavDestination.CHAT -> WebChatDestination(
                            state = WebChatDestinationState(
                                compact = compact,
                                roster = WebChatRoster(agents, selectedAgent, connectionState, uiError),
                                chat = chat,
                                canvas = WebChatCanvas(canvasStore, savedSettings.openChatsOnCanvas),
                            ),
                            actions = WebChatShellActions(
                                onAgentSelected = ::selectAgent,
                                onSettings = { destination = WebNavDestination.SETTINGS },
                                onShowAgents = { showSidebar = true },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        WebNavDestination.SETTINGS -> WebSettingsPane(
                            modifier = Modifier.weight(1f),
                            compact = compact,
                            config = config,
                            onConfigSaved = { saved ->
                                config = saved
                                settingsStore.saveBackend(saved)
                            },
                            onTokenCleared = { config = config.copy(accessToken = null) },
                            onBack = { destination = WebNavDestination.CHAT },
                        )
                    }
                }
            }
        }
    }
}

private val CompactWidth = 720.dp
