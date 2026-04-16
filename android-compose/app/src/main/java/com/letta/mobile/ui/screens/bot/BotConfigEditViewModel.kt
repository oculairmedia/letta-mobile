package com.letta.mobile.ui.screens.bot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.config.BotScheduledJob
import com.letta.mobile.bot.core.ConversationMode
import com.letta.mobile.bot.skills.BotSkillRegistry
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.domain.AgentSearch
import com.letta.mobile.ui.navigation.BotConfigEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BotConfigEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val configStore: BotConfigStore,
    private val agentRepository: AgentRepository,
    private val agentSearch: AgentSearch,
    private val skillRegistry: BotSkillRegistry,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BotConfigEditRoute>()
    val isEditing: Boolean = route.configId != null

    val agents: StateFlow<List<Agent>> = agentRepository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var agentSearchQuery by mutableStateOf("")
    var agentSearchExpanded by mutableStateOf(false)

    var displayName by mutableStateOf("")
    var agentId by mutableStateOf("")
    var selectedAgentName by mutableStateOf<String?>(null)
    var mode by mutableStateOf(BotConfig.Mode.LOCAL)
    var remoteUrl by mutableStateOf("")
    var remoteToken by mutableStateOf("")
    var enabled by mutableStateOf(true)

    var conversationMode by mutableStateOf(ConversationMode.PER_CHAT)
    var sharedConversationId by mutableStateOf("")
    var channels by mutableStateOf("in_app")

    var heartbeatEnabled by mutableStateOf(false)
    var heartbeatIntervalMinutes by mutableLongStateOf(60L)
    var heartbeatMessage by mutableStateOf(BotConfig.DEFAULT_HEARTBEAT_MESSAGE)
    var heartbeatRequiresCharging by mutableStateOf(false)
    var heartbeatRequiresUnmeteredNetwork by mutableStateOf(false)

    var apiServerEnabled by mutableStateOf(false)
    var apiServerPort by mutableIntStateOf(8080)
    var apiServerToken by mutableStateOf("")

    var autoStart by mutableStateOf(false)
    var directivesEnabled by mutableStateOf(true)
    var envelopeTemplate by mutableStateOf("")
    var contextProviders by mutableStateOf("")
    var enabledSkills by mutableStateOf("")

    val scheduledJobs = mutableStateListOf<BotScheduledJob>()
    val availableSkillIds: String = skillRegistry.listAvailableSkills().joinToString(", ") { it.id }

    private var configId: String = route.configId ?: UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            try { agentRepository.refreshAgentsIfStale(60_000) } catch (_: Exception) {}
        }
        if (route.configId != null) {
            viewModelScope.launch { loadConfig(route.configId) }
        }
    }

    fun filteredAgents(): List<Agent> {
        return agentSearch.search(agents.value, agentSearchQuery)
    }

    fun selectAgent(agent: Agent) {
        agentId = agent.id
        selectedAgentName = agent.name
        agentSearchQuery = ""
        agentSearchExpanded = false
    }

    fun clearAgentSelection() {
        agentId = ""
        selectedAgentName = null
    }

    private suspend fun loadConfig(id: String) {
        val configs = configStore.configs.first()
        val config = configs.find { it.id == id } ?: return
        configId = config.id
        displayName = config.displayName
        agentId = config.agentId
        mode = config.mode
        remoteUrl = config.remoteUrl ?: ""
        remoteToken = config.remoteToken ?: ""
        enabled = config.enabled
        conversationMode = config.conversationMode
        sharedConversationId = config.sharedConversationId ?: ""
        channels = config.channels.joinToString(", ")
        heartbeatEnabled = config.heartbeatEnabled
        heartbeatIntervalMinutes = config.heartbeatIntervalMinutes
        heartbeatMessage = config.heartbeatMessage
        heartbeatRequiresCharging = config.heartbeatRequiresCharging
        heartbeatRequiresUnmeteredNetwork = config.heartbeatRequiresUnmeteredNetwork
        apiServerEnabled = config.apiServerEnabled
        apiServerPort = config.apiServerPort
        apiServerToken = config.apiServerToken ?: ""
        autoStart = config.autoStart
        directivesEnabled = config.directivesEnabled
        envelopeTemplate = config.envelopeTemplate ?: ""
        contextProviders = config.contextProviders.joinToString(", ")
        enabledSkills = config.enabledSkills.joinToString(", ")
        scheduledJobs.clear()
        scheduledJobs.addAll(config.scheduledJobs)

        // Resolve agent name from cached agents
        val cachedAgent = agentRepository.getCachedAgent(config.agentId)
        selectedAgentName = cachedAgent?.name
    }

    fun addScheduledJob() {
        scheduledJobs.add(
            BotScheduledJob(
                id = UUID.randomUUID().toString(),
                displayName = "",
                message = "",
                cronExpression = "",
            )
        )
    }

    fun removeScheduledJob(id: String) {
        scheduledJobs.removeAll { it.id == id }
    }

    fun updateScheduledJob(updated: BotScheduledJob) {
        val index = scheduledJobs.indexOfFirst { it.id == updated.id }
        if (index >= 0) scheduledJobs[index] = updated
    }

    fun save(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (agentId.isBlank()) {
            onError("Agent ID is required")
            return
        }
        val config = BotConfig(
            id = configId,
            agentId = agentId.trim(),
            displayName = displayName.trim(),
            mode = mode,
            remoteUrl = remoteUrl.trim().ifBlank { null },
            remoteToken = remoteToken.trim().ifBlank { null },
            conversationMode = conversationMode,
            sharedConversationId = sharedConversationId.trim().ifBlank { null },
            channels = channels.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            envelopeTemplate = envelopeTemplate.trim().ifBlank { null },
            directivesEnabled = directivesEnabled,
            contextProviders = contextProviders.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            enabledSkills = enabledSkills.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoStart = autoStart,
            heartbeatEnabled = heartbeatEnabled,
            heartbeatIntervalMinutes = heartbeatIntervalMinutes,
            heartbeatMessage = heartbeatMessage,
            heartbeatRequiresCharging = heartbeatRequiresCharging,
            heartbeatRequiresUnmeteredNetwork = heartbeatRequiresUnmeteredNetwork,
            scheduledJobs = scheduledJobs.toList(),
            enabled = enabled,
            apiServerEnabled = apiServerEnabled,
            apiServerPort = apiServerPort,
            apiServerToken = apiServerToken.trim().ifBlank { null },
        )
        viewModelScope.launch {
            try {
                configStore.saveConfig(config)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to save config")
            }
        }
    }
}
