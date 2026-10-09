package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.activeBackendIsIroh
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The shared navigation drawer's binding for the chat scaffold; null (tests, hosts without Hilt)
 * keeps the legacy drawer. [AgentScaffold] provides it.
 */
internal val LocalSharedNavDrawer = staticCompositionLocalOf<SharedNavDrawerViewModel?> { null }

/**
 * The Android side of the shared navigation drawer (letta-mobile-c3np7.5.5): whether it replaces the
 * legacy chat drawer, the canvases it lists, its archive filter, and the conversation actions its
 * rows offer (conversation archive / delete, agent pins). The drawer's look and its mapping live in sharedUI; this only binds Android's stores.
 */
@HiltViewModel
internal class SharedNavDrawerViewModel @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val canvasStore: CanvasDocumentStore,
    private val allConversations: IAllConversationsRepository,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settingsRepository.getFeatureFlag(FeatureFlag.SharedNavDrawer)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Agents pinned to Home; the rail orbs' menu pins and unpins them. */
    val pinnedAgentIds: StateFlow<Set<String>> = settingsRepository.getPinnedAgentIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /** Conversations the user pinned; the drawer lists them first and its row menus pin and unpin them. */
    val pinnedConversationIds: StateFlow<Set<String>> = settingsRepository.getPinnedConversationIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /**
     * Each agent's newest conversation activity across the fleet, for the rail's recents cut. An
     * agent's own record rarely carries a last run, so this is the rail's main recency signal.
     */
    val agentActivity: StateFlow<Map<String, Instant>> = allConversations.conversations
        .map { RecentAgents.lastActiveAtFromConversations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val _canvases = MutableStateFlow<List<CanvasDocument>>(emptyList())
    val canvases: StateFlow<List<CanvasDocument>> = _canvases.asStateFlow()

    private val _archiveFilter = MutableStateFlow(ShellArchiveFilter.Active)
    val archiveFilter: StateFlow<ShellArchiveFilter> = _archiveFilter.asStateFlow()

    /** Re-reads the canvas library; the drawer calls it each time it opens. */
    fun refreshCanvases() {
        viewModelScope.launch {
            _canvases.value = runCatchingNonCancel { canvasStore.listAll() } ?: _canvases.value
        }
    }

    /** Brings the fleet's conversation list up to date (a cached list stays); the drawer calls it each time it opens. */
    fun refreshAgentActivity() {
        viewModelScope.launch {
            runCatchingNonCancel { allConversations.refreshIfStale(ACTIVITY_MAX_AGE_MS) }
        }
    }

    fun setArchiveFilter(filter: ShellArchiveFilter) {
        _archiveFilter.value = filter
    }

    /**
     * The conversation actions take the repository the drawer LISTS from (the scaffold's own, which
     * can differ from any injected one): a write through another repository would miss this one's
     * cache, and a rename would silently do nothing.
     */
    fun setConversationArchived(repository: IConversationRepository, conversationId: String, agentId: String, archived: Boolean) {
        viewModelScope.launch {
            reportFailure("Couldn't ${if (archived) "archive" else "restore"} the chat") {
                repository.setConversationArchived(conversationId, agentId, archived)
            }
        }
    }

    fun setAgentPinned(agentId: String, pinned: Boolean) {
        viewModelScope.launch {
            reportFailure("Couldn't ${if (pinned) "pin" else "unpin"} the agent") { settingsRepository.setAgentPinned(agentId, pinned) }
        }
    }

    /** The Iroh backend has no delete command, so a delete there only archives (it stays under Archived); the confirm dialog says so. */
    val deleteBehavior: ConversationDeleteBehavior
        get() = if (settingsRepository.activeBackendIsIroh()) {
            ConversationDeleteBehavior.MovesToArchived
        } else {
            ConversationDeleteBehavior.Permanent
        }

    fun setConversationPinned(conversationId: ConversationId, pinned: Boolean) {
        viewModelScope.launch {
            reportFailure("Couldn't ${if (pinned) "pin" else "unpin"} the chat") { settingsRepository.setConversationPinned(conversationId.value, pinned) }
        }
    }

    /** A blank title is ignored; the row keeps its name. */
    fun renameConversation(
        repository: IConversationRepository,
        conversationId: ConversationId,
        agentId: AgentId,
        title: ConversationSummary,
    ) {
        val trimmed = title.value.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            reportFailure("Couldn't rename the chat") { repository.updateConversation(conversationId, agentId, trimmed) }
        }
    }

    fun deleteConversation(repository: IConversationRepository, conversationId: String, agentId: String) {
        viewModelScope.launch {
            reportFailure("Couldn't delete the chat") { repository.deleteConversation(conversationId, agentId) }
        }
    }

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = FAILURE_BUFFER)

    /** A conversation action that failed, as a message for the drawer to show. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    private suspend fun reportFailure(message: String, block: suspend () -> Unit) {
        if (runCatchingNonCancel { block() } == null) _failures.tryEmit(message)
    }

    /** A failed store call leaves the drawer as it was; cancellation still propagates. */
    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w(TAG, "shared nav drawer store call failed", error)
            null
        }

    private companion object {
        const val TAG = "SharedNavDrawer"
        const val STOP_TIMEOUT_MS = 5_000L
        const val FAILURE_BUFFER = 4
        const val ACTIVITY_MAX_AGE_MS = 60_000L
    }
}
