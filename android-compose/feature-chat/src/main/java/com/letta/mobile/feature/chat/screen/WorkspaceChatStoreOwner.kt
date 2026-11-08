package com.letta.mobile.feature.chat.screen

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.RememberObserver
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.enableSavedStateHandles
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.navigation.NavBackStackEntry
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.toArgs
import com.letta.mobile.feature.chat.route.toViewModelKey

/** Source-scoped retained chat VM and fresh/exact aliases, with an ephemeral route handle. */
class WorkspaceChatStoreOwner internal constructor(
    private val key: String,
    private val drawer: SharedNavDrawerViewModel,
    private val parent: NavBackStackEntry,
    private val context: Context,
    private val route: AgentChatRoute,
    private val workspace: ShellWorkspaceState,
    private val openConversation: (com.letta.mobile.data.model.AgentId, com.letta.mobile.data.model.ConversationId) -> Unit,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory, SavedStateRegistryOwner, RememberObserver {
    override val viewModelStore: ViewModelStore = drawer.chatStore(key)
    private val request = workspace.requestToken()
    private var chatModel: AdminChatViewModel? = null
    private val chatLifecycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = chatLifecycle
    private val registry = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = registry.savedStateRegistry
    private val parentEvents = LifecycleEventObserver { _, event -> chatLifecycle.handleLifecycleEvent(event) }

    init {
        registry.performAttach()
        enableSavedStateHandles()
        // Workspace focus restores identity. Never restore a competing route/initial-send handle.
        registry.performRestore(null)
        chatLifecycle.currentState = Lifecycle.State.CREATED
    }

    private val factory = HiltViewModelFactory(context, parent.defaultViewModelProviderFactory)
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            factory.create(modelClass, extras).also { model ->
                (model as? AdminChatViewModel)?.let(::bind)
            }
    }

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(parent.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, route.toArgs().apply { putBoolean("workspaceOwned", true) })
            set(VIEW_MODEL_STORE_OWNER_KEY, this@WorkspaceChatStoreOwner)
            set(SAVED_STATE_REGISTRY_OWNER_KEY, this@WorkspaceChatStoreOwner)
        }

    internal fun bind(model: AdminChatViewModel) {
        chatModel = model
        bindRetained(model, drawer, workspace, route, key, openConversation)
    }

    companion object {
        fun claimInputs(drawer: SharedNavDrawerViewModel, inputs: com.letta.mobile.data.shell.CurrentFocusInputs): Boolean {
            if (drawer.appliedInputs === inputs) return false
            drawer.appliedInputs = inputs
            return true
        }
        fun bindSource(drawer: SharedNavDrawerViewModel, source: String) {
            if (drawer.chatSource == source) return
            clearRetained(drawer)
            drawer.chatSource = source
        }
        internal fun clearRetained(drawer: SharedNavDrawerViewModel) {
            drawer.chatStores.values.distinct().forEach(ViewModelStore::clear)
            drawer.chatStores.clear()
        }
        /** A retiring Activity must not replace the active workspace during its final recomposition. */
        fun claimOwner(
            drawer: SharedNavDrawerViewModel, workspace: ShellWorkspaceState, context: Context, ready: Boolean = true,
        ): Boolean {
            if (activity(context)?.isChangingConfigurations == true) return false
            drawer.workspaceReady = ready
            drawer.workspaceOwner = workspace
            return true
        }
        fun isCurrentOwner(drawer: SharedNavDrawerViewModel, workspace: ShellWorkspaceState): Boolean =
            drawer.workspaceOwner === workspace && drawer.workspaceReady &&
                drawer.sources.sessions.current.id == workspace.sourceGeneration

        /** Rebind retained work even when a restored editor covers the chat. */
        fun rebindRetained(
            drawer: SharedNavDrawerViewModel, workspace: ShellWorkspaceState, retainRequest: Boolean = true,
            open: (com.letta.mobile.data.model.AgentId, com.letta.mobile.data.model.ConversationId) -> Unit,
        ) {
            val agent = workspace.currentFocus.agentId ?: return
            val route = AgentChatRoute(agent.value, conversationId = workspace.currentFocus.conversationId?.value)
            val key = focusKey(workspace, route)
            val store = drawer.chatStores[key] ?: return
            store.keys().mapNotNull { store[it] as? AdminChatViewModel }.forEach {
                bindRetained(it, drawer, workspace, route, key, open, retainRequest)
            }
        }
        private fun bindRetained(
            model: AdminChatViewModel, drawer: SharedNavDrawerViewModel, workspace: ShellWorkspaceState,
            route: AgentChatRoute, key: String,
            open: (com.letta.mobile.data.model.AgentId, com.letta.mobile.data.model.ConversationId) -> Unit,
            retainRequest: Boolean = true,
        ) {
            model.bindWorkspace(workspace, open, { isCurrentOwner(drawer, workspace) }, retainRequest) { conversation ->
                val exact = route.copy(conversationId = conversation.value, freshRouteKey = null, initialMessage = null)
                drawer.chatStore(key, focusKey(workspace, exact, CurrentFocusChatMode.Exact))
            }
        }
        private tailrec fun activity(context: Context): Activity? = when (context) {
            is Activity -> context
            is ContextWrapper -> activity(context.baseContext)
            else -> null
        }
        fun focusKey(
            workspace: ShellWorkspaceState, route: AgentChatRoute,
            mode: CurrentFocusChatMode = workspace.currentFocus.chatMode,
        ): String {
            val phase = if (route.freshRouteKey != null) CurrentFocusChatMode.New else mode
            return "${checkNotNull(workspace.sourceKey)}:$phase:${route.toViewModelKey()}"
        }
        fun retain(workspace: ShellWorkspaceState, route: AgentChatRoute, drawer: SharedNavDrawerViewModel): String {
            val key = focusKey(workspace, route)
            if (drawer.workspaceOwner != null && !isCurrentOwner(drawer, workspace)) return key
            bindSource(drawer, checkNotNull(workspace.sourceKey))
            if (route.freshRouteKey != null) {
                drawer.chatStore(key, focusKey(workspace, route.copy(freshRouteKey = null), CurrentFocusChatMode.New))
            }
            workspace.currentFocus.conversationId?.let { conversation ->
                val exact = route.copy(conversationId = conversation.value, freshRouteKey = null, initialMessage = null)
                drawer.chatStore(key, focusKey(workspace, exact, CurrentFocusChatMode.Exact))
            }
            return key
        }
    }

    override fun onRemembered() { parent.lifecycle.addObserver(parentEvents) }

    // The retained workspace owns all chat stores, including tabs hidden by the editor. Source
    // changes and ViewModel clearing release them; a composition leaving is not a lifecycle end.
    override fun onForgotten() {
        parent.lifecycle.removeObserver(parentEvents)
        chatLifecycle.currentState = Lifecycle.State.DESTROYED
        if (activity(context)?.isChangingConfigurations != true && !retained()) drawer.releaseChat(key)
    }
    private fun retained(): Boolean {
        val model = chatModel
        val conversation = model?.conversationId?.value ?: route.conversationId
        val inTabs = conversation != null && conversation in workspace.tabs.openConversationIds
        val selected = workspace.focusRevision == request.focusRevision && workspace.currentFocus.agentId?.value == route.agentId
        val busy = model?.uiState?.value?.let { it.isStreaming || it.isAgentTyping } == true
        return selected || inTabs || busy
    }
    override fun onAbandoned() = onForgotten()
}
