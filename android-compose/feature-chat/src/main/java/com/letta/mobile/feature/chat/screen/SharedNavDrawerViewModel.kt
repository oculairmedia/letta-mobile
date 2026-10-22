package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatDisplayPrefs
import com.letta.mobile.data.chat.runtime.ConversationListPrefs
import com.letta.mobile.data.chat.runtime.ConversationMarker
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.activeBackendUsesChannel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.ui.shell.pages.ShellLibrarySources
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarkActions
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/** The workspace's drawer binding, for the chat page's display options and `/` commands. */
internal val LocalSharedNavDrawer = staticCompositionLocalOf<SharedNavDrawerViewModel?> { null }

/** What the shared shell workspace reads on Android besides the drawer's own stores (letta-mobile-ckmub.2). */
class SharedNavDrawerSources @Inject constructor(
    val agents: IAgentRepository,
    val conversations: IConversationRepository,
    val sessions: SessionManager,
    val library: ShellLibrarySources,
    val modelControl: ModelControlSession,
    /** The agent editor's creator, and what its Dreaming panel reads (the host's `reflection.*`, the MemFS dream log). */
    val creator: AgentCreator,
    val dreamingRpc: AdminRpcInvoker,
)

/**
 * The Android side of the shared shell workspace's navigation (letta-mobile-c3np7.5.5, ckmub.2): the
 * roster, the fleet's and the focused agent's conversations, the canvases the panel lists, its archive
 * filter, and what its rows do (archive, delete, rename, pin; agent pins). The look and the mapping
 * live in sharedUI; this only binds Android's stores.
 */
@HiltViewModel
class SharedNavDrawerViewModel @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val canvasStore: CanvasDocumentStore,
    private val allConversations: IAllConversationsRepository,
    secureSettings: SecureSettingsStore,
    val sources: SharedNavDrawerSources,
    /** letta-mobile-7mys7 (G37): each fork's source conversation; null in tests. */
    private val lineage: com.letta.mobile.data.chat.branch.ConversationLineage? = null,
) : ViewModel() {
    /** letta-mobile-zxk2n.5: settled chats and markers (client marks), and the Chat options' display toggles. */
    private val listMarks = ConversationListPrefs(secureSettings)
    private val displayPrefs = ChatDisplayPrefs(secureSettings)

    val settledConversationIds: StateFlow<Set<String>> = listMarks.settled
    val conversationMarkers: StateFlow<Map<String, String>> = listMarks.markers
    val displayOptions: StateFlow<ChatDisplayOptions> = displayPrefs.options
    val agents: StateFlow<List<Agent>> = sources.agents.agents
    /** Every conversation the fleet list knows, for the shell's "open this agent" and its tabs. */
    val fleet: StateFlow<List<Conversation>> = allConversations.conversations

    /** [agent]'s own conversations, as the panel lists them. */
    fun conversationsOf(agent: AgentId): Flow<List<Conversation>> = sources.conversations.getConversations(agent)

    /** Settle / move to active and the marker, for the drawer's row menus. */
    val conversationMarkActions = ShellConversationMarkActions(
        onSettle = { id, settled -> listMarks.setSettled(ConversationId(id), settled) },
        onSetMarker = { id, marker -> listMarks.setMarker(ConversationId(id), marker?.let(::ConversationMarker)) },
        markerChoices = ConversationListPrefs.MARKER_CHOICES,
    )

    fun setDisplayOptions(options: ChatDisplayOptions) = displayPrefs.update { options }

    /** Whether the active backend is Iroh, where `/recompile` has no route yet. */
    val backendIsIroh: Boolean get() = settingsRepository.activeBackendUsesChannel()

    /** Each fork's source conversation id, so a fork's row reads "Forked from …". */
    val conversationParents: StateFlow<Map<String, String>> =
        (lineage?.parents ?: MutableStateFlow(emptyMap()))
            .map { parents -> parents.mapValues { it.value.conversationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Agents pinned to Home; the rail orbs' menu pins and unpins them. */
    val pinnedAgentIds: StateFlow<Set<String>> = settingsRepository.getPinnedAgentIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /** Conversations the user pinned; the drawer lists them first and its row menus pin and unpin them. */
    val pinnedConversationIds: StateFlow<Set<String>> = settingsRepository.getPinnedConversationIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /** Each agent's newest conversation activity across the fleet. */
    val agentActivity: StateFlow<Map<String, Instant>> = allConversations.conversations
        .map { RecentAgents.lastActiveAtFromConversations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val _canvases = MutableStateFlow<List<CanvasDocument>>(emptyList())
    val canvases: StateFlow<List<CanvasDocument>> = _canvases.asStateFlow()
    private val _archiveFilter = MutableStateFlow(ShellArchiveFilter.Active)
    val archiveFilter: StateFlow<ShellArchiveFilter> = _archiveFilter.asStateFlow()

    /** Re-reads the canvases and brings the fleet's and [agent]'s lists up to date (cached ones stay); as the panel opens. */
    fun refresh(agent: AgentId?, roster: Boolean = false) {
        if (roster) run("Couldn't reload the agents") { sources.agents.refreshAgents() }
        viewModelScope.launch { _canvases.value = runCatchingNonCancel { canvasStore.listAll() } ?: _canvases.value }
        viewModelScope.launch { runCatchingNonCancel { allConversations.refreshIfStale(ACTIVITY_MAX_AGE_MS) } }
        if (agent == null) return
        viewModelScope.launch { runCatchingNonCancel { sources.conversations.refreshConversationsIfStale(agent, ACTIVITY_MAX_AGE_MS) } }
    }

    fun setArchiveFilter(filter: ShellArchiveFilter) = _archiveFilter.update { filter }


    fun setConversationArchived(conversation: ConversationId, agent: AgentId, archived: Boolean) =
        run("Couldn't ${if (archived) "archive" else "restore"} the chat") {
            sources.conversations.setConversationArchived(conversation.value, agent.value, archived)
        }

    fun setAgentPinned(agent: AgentId, pinned: Boolean) = run("Couldn't ${if (pinned) "pin" else "unpin"} the agent") {
        settingsRepository.setAgentPinned(agent.value, pinned)
    }

    /** The Iroh backend has no delete command, so a delete there only archives (it stays under Archived); the confirm dialog says so. */
    val deleteBehavior: ConversationDeleteBehavior
        get() = ConversationDeleteBehavior.MovesToArchived.takeIf { settingsRepository.activeBackendUsesChannel() }
            ?: ConversationDeleteBehavior.Permanent

    fun setConversationPinned(id: ConversationId, pinned: Boolean) =
        run("Couldn't ${if (pinned) "pin" else "unpin"} the chat") { settingsRepository.setConversationPinned(id.value, pinned) }

    /** A blank title is ignored; the row keeps its name. */
    fun renameConversation(conversationId: ConversationId, agentId: AgentId, title: ConversationSummary) {
        val trimmed = title.value.trim()
        if (trimmed.isEmpty()) return
        run("Couldn't rename the chat") { sources.conversations.updateConversation(conversationId, agentId, trimmed) }
    }

    fun deleteConversation(conversation: ConversationId, agent: AgentId) = run("Couldn't delete the chat") {
        sources.conversations.deleteConversation(conversation.value, agent.value)
    }

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = FAILURE_BUFFER)
    /** A conversation action that failed, as a message for the drawer to show. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch { if (runCatchingNonCancel { block() } == null) _failures.tryEmit(failure) }
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

    /** The chat page's view models, a store per chat kept across a rotation; a chat left behind is cleared. */
    private val chatStores = mutableMapOf<String, ViewModelStore>()
    fun chatStore(key: String): ViewModelStore = chatStores.getOrPut(key) { ViewModelStore() }

    fun releaseChat(key: String) {
        chatStores.remove(key)?.clear()
    }

    override fun onCleared() {
        chatStores.values.forEach(ViewModelStore::clear)
        chatStores.clear()
    }

    private companion object {
        const val TAG = "SharedNavDrawer"
        const val STOP_TIMEOUT_MS = 5_000L
        const val FAILURE_BUFFER = 4
        const val ACTIVITY_MAX_AGE_MS = 60_000L
    }
}
