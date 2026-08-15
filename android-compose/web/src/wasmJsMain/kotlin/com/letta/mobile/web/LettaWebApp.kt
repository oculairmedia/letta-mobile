package com.letta.mobile.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WasmAppServerClientGateway
import com.letta.mobile.web.data.WebChatEntry
import com.letta.mobile.web.data.WebConnectionState
import com.letta.mobile.web.fs.WebWorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class WebNavDestination { CHAT, SETTINGS }

@Composable
fun LettaWebApp() {
    val scope = rememberCoroutineScope()
    val workspace = remember { WebWorkspaceController() }
    val gateway = remember { WasmAppServerClientGateway(scope) }
    val connectionState by gateway.state.collectAsState()
    val agents = remember { mutableStateListOf<AgentItemState>() }
    val messagesByAgent = remember { mutableStateMapOf<String, List<WebChatEntry>>() }
    var config by remember {
        mutableStateOf(
            LettaConfig(
                id = "default",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "",
                accessToken = null,
            ),
        )
    }
    var destination by remember { mutableStateOf(WebNavDestination.CHAT) }
    var showSidebar by remember { mutableStateOf(true) }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var isLoadingAgents by remember { mutableStateOf(false) }
    var isLoadingConversation by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<String?>(null) }
    var localMessageSequence by remember { mutableIntStateOf(0) }
    var refreshSequence by remember { mutableIntStateOf(0) }

    val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
    val messages = selectedAgentId?.let(messagesByAgent::get).orEmpty()

    LaunchedEffect(config.serverUrl, config.accessToken, refreshSequence) {
        isLoadingAgents = true
        uiError = null
        try {
            val fetched = gateway.listAgents(config)
            agents.clear()
            agents.addAll(fetched)
            selectedAgentId = selectedAgentId?.takeIf { id -> fetched.any { it.id == id } }
                ?: fetched.firstOrNull()?.id
            if (fetched.isEmpty()) messagesByAgent.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            agents.clear()
            selectedAgentId = null
            messagesByAgent.clear()
            uiError = error.message ?: "Connection failed"
        } finally {
            isLoadingAgents = false
        }
    }

    LaunchedEffect(selectedAgentId, connectionState) {
        val agentId = selectedAgentId ?: return@LaunchedEffect
        if (connectionState !is WebConnectionState.Connected) return@LaunchedEffect
        isLoadingConversation = true
        uiError = null
        try {
            messagesByAgent[agentId] = gateway.loadConversation(agentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            uiError = error.message ?: "Conversation load failed"
        } finally {
            isLoadingConversation = false
        }
    }

    fun selectAgent(agent: AgentItemState) {
        selectedAgentId = agent.id
        destination = WebNavDestination.CHAT
    }

    fun sendMessage() {
        val agent = selectedAgent ?: return
        val text = input.trim().takeIf(String::isNotEmpty) ?: return
        if (connectionState !is WebConnectionState.Connected || isSending) return
        input = ""
        localMessageSequence += 1
        val userId = "local-user-$localMessageSequence"
        val assistantId = "local-assistant-$localMessageSequence"
        messagesByAgent[agent.id] = messagesByAgent[agent.id].orEmpty() +
            WebChatEntry(userId, "You", text, true)
        isSending = true
        uiError = null
        scope.launch {
            try {
                gateway.sendMessage(agent.id, text).collect { assistantText ->
                    val current = messagesByAgent[agent.id].orEmpty().filterNot { it.id == assistantId }
                    messagesByAgent[agent.id] = current +
                        WebChatEntry(assistantId, agent.name, assistantText, false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                uiError = error.message ?: "Agent turn failed"
            } finally {
                isSending = false
            }
        }
    }

    SharedMaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val compact = maxWidth < 720.dp
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
                            onOpenWorkspace = {
                                scope.launch {
                                    if (workspace.openWorkspaceDirectory()) selectedWorkspace = workspace.workspaceName
                                }
                            },
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
                        WebNavDestination.CHAT -> WebChatPane(
                            modifier = Modifier.weight(1f),
                            compact = compact,
                            agents = agents,
                            selectedAgent = selectedAgent,
                            connectionState = connectionState,
                            messages = messages,
                            input = input,
                            isLoadingConversation = isLoadingConversation,
                            isSending = isSending,
                            error = uiError,
                            workspaceName = selectedWorkspace,
                            onInputChanged = { input = it },
                            onAgentSelected = ::selectAgent,
                            onSend = ::sendMessage,
                            onOpenWorkspace = {
                                scope.launch {
                                    if (workspace.openWorkspaceDirectory()) selectedWorkspace = workspace.workspaceName
                                }
                            },
                            onSettings = { destination = WebNavDestination.SETTINGS },
                        )
                        WebNavDestination.SETTINGS -> WebSettingsPane(
                            modifier = Modifier.weight(1f),
                            compact = compact,
                            config = config,
                            onConfigSaved = { config = it },
                            onTokenCleared = { config = config.copy(accessToken = null) },
                            onBack = { destination = WebNavDestination.CHAT },
                        )
                    }
                }
            }
        }
    }
}
