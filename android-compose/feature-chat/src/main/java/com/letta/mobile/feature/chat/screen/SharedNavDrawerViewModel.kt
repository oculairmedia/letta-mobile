package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.ui.shell.sidebar.ShellArchiveFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val conversationRepository: IConversationRepository,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settingsRepository.getFeatureFlag(FeatureFlag.SharedNavDrawer)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Agents pinned to Home; the rail orbs' menu pins and unpins them. */
    val pinnedAgentIds: StateFlow<Set<String>> = settingsRepository.getPinnedAgentIds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

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

    fun setArchiveFilter(filter: ShellArchiveFilter) {
        _archiveFilter.value = filter
    }

    fun setConversationArchived(conversationId: String, agentId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatchingNonCancel { conversationRepository.setConversationArchived(conversationId, agentId, archived) }
        }
    }

    fun setAgentPinned(agentId: String, pinned: Boolean) {
        viewModelScope.launch {
            runCatchingNonCancel { settingsRepository.setAgentPinned(agentId, pinned) }
        }
    }

    fun deleteConversation(conversationId: String, agentId: String) {
        viewModelScope.launch {
            runCatchingNonCancel { conversationRepository.deleteConversation(conversationId, agentId) }
        }
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
    }
}
