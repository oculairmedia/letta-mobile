package com.letta.mobile.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.home.FleetOverviewParams
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.HomeMessageSearch
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.SessionHomePageSource
import com.letta.mobile.data.home.buildFleetOverview
import com.letta.mobile.data.home.toFleetConversation
import com.letta.mobile.data.model.toParsed
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IMessageRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The repositories the Android Home binding reads beyond the session graph. */
class DashboardRepositories @Inject constructor(
    val agents: IAgentRepository,
    val conversations: IAllConversationsRepository,
    val messages: IMessageRepository,
    val settings: ISettingsRepository,
)

/**
 * Android binding for the shared Home page (letta-mobile-c3np7.3.11.1): a [HomePageController] over
 * the session graph (the same source desktop binds) plus Android's message search, with the pins in
 * [ISettingsRepository] and the fleet folded from the conversation list. Search, pin resolution,
 * stats and the fleet model all live in sharedLogic.
 */
@HiltViewModel
class DashboardViewModel internal constructor(
    sessionGraphs: SessionRepositoryGraphProvider<SessionGraph>,
    private val repositories: DashboardRepositories,
) : ViewModel() {
    @Inject
    constructor(sessionManager: SessionManager, repositories: DashboardRepositories) :
        this(sessionGraphs = sessionManager, repositories = repositories)

    private val controller = HomePageController(
        source = SessionHomePageSource(
            sessionGraphProvider = sessionGraphs,
            scope = viewModelScope,
            messageSearch = HomeMessageSearch { query ->
                repositories.messages.searchMessages(HomeMessageSearch.request(query)).map { it.toParsed() }
            },
        ),
        pins = SettingsHomePinStore(repositories.settings, viewModelScope),
        scope = viewModelScope,
    )

    val state: StateFlow<HomePageState> = controller.state
    val actions: HomePageActions = controller

    init {
        migrateAdminToFavorite()
        controller.start()
        followFleet()
        followFavorite()
        rememberPinnedAgentNames()
        refreshConversations()
        viewModelScope.launch {
            // letta-mobile-ze5l: a backend switch refetches the conversation list (the graph's own
            // switch reloads the stats and catalog).
            repositories.settings.activeConfigChanges.collect { refreshConversations() }
        }
    }

    override fun onCleared() {
        controller.close()
    }

    private fun migrateAdminToFavorite() {
        val settings = repositories.settings
        if (settings.favoriteAgentId.value == null && settings.adminAgentId.value != null) {
            settings.setFavoriteAgentId(settings.adminAgentId.value)
        }
    }

    private fun followFleet() {
        val conversations = repositories.conversations
        viewModelScope.launch {
            combine(conversations.conversations, repositories.agents.agents, conversations.hasMore) { list, agents, hasMore ->
                buildFleetOverview(
                    FleetOverviewParams(
                        conversations = list.mapNotNull { it.toFleetConversation() },
                        rosterAgents = agents,
                        conversationsTruncated = hasMore,
                    ),
                )
            }.collect(controller::updateFleet)
        }
    }

    private fun followFavorite() {
        viewModelScope.launch {
            combine(repositories.settings.favoriteAgentId, repositories.agents.agents) { id, agents ->
                id?.let { favoriteId -> HomeAgentRef(favoriteId, agents.firstOrNull { it.id.value == favoriteId }?.name ?: favoriteId) }
            }.collect(controller::updateFavorite)
        }
    }

    /**
     * Write-through of pinned agents' live names, so after a backend switch their tiles render from
     * the cache at once instead of waiting for the new agent list.
     */
    private fun rememberPinnedAgentNames() {
        val settings = repositories.settings
        viewModelScope.launch {
            combine(settings.getPinnedItemsOrder(), repositories.agents.agents, settings.getPinnedAgentNames()) { keys, agents, cached ->
                val pinned = keys.mapNotNull(HomePinnedItem::parseAgentKey).toSet()
                agents.filter { it.id.value in pinned && cached[it.id.value] != it.name }
            }.collect { stale -> stale.forEach { settings.upsertPinnedAgentName(it.id.value, it.name) } }
        }
    }

    private fun refreshConversations() {
        viewModelScope.launch {
            val failure = runCatching { repositories.conversations.refresh() }.exceptionOrNull() ?: return@launch
            if (failure is CancellationException) throw failure
            Telemetry.error("DashboardVM", "conversation refresh failed", failure)
        }
    }
}
