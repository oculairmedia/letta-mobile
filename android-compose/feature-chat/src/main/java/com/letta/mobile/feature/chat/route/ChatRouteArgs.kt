package com.letta.mobile.feature.chat.route
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.CurrentFocusRequest
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.session.SessionRepositoryGraph
import javax.inject.Inject
/** Route inputs are consumed once; a bound workspace supplies every subsequent selection read. */
internal class ChatRouteArgs @Inject constructor(private val savedStateHandle: SavedStateHandle) {
    val agentId: String = requireNotNull(savedStateHandle.get<String>("agentId")) {
        "Missing agentId in AdminChatViewModel navigation arguments"
    }
    val initialAgentName = savedStateHandle.get<String>("agentName")?.takeIf(String::isNotBlank)
    val initialMessage = savedStateHandle.get<String>("initialMessage")
    val initialChatMode = savedStateHandle.get<String>("initialChatMode")?.takeIf(String::isNotBlank)
    val scrollToMessageId = savedStateHandle.get<String>("scrollToMessageId")
    val workspaceOwned = savedStateHandle.get<Boolean>("workspaceOwned") == true
    private val freshKey = savedStateHandle.get<Long>("freshRouteKey")
    private val entryConversation = savedStateHandle.get<String>("conversationId")
    val isFreshRoute = freshKey != null || entryConversation?.isBlank() == true
    val explicitNewChat = freshKey != null
    private var runtimeConversation = entryConversation?.takeIf { it.isNotBlank() && !explicitNewChat }?.let(::ConversationId)
    var workspace: ShellWorkspaceState? = null
        private set
    private var request: CurrentFocusRequest? = null
    private var boundSource: String? = null
    private var reboundRequests = emptySet<CurrentFocusRequest>()
    var session: SessionRepositoryGraph? = null
    var isOwnerCurrent: () -> Boolean = { true }
    var liveGeneration: () -> Long? = { workspace?.sourceGeneration }
    var onSettled: (ConversationId) -> Unit = {}
    val explicitConversationId: String?
        get() = when {
            workspace == null -> runtimeConversation?.value
            isCurrent() -> workspace?.currentFocus?.conversationId?.let { subagentConversation(agentId, it.value) }
            else -> runtimeConversation?.value
        }
    /** Explicit lifecycle rebind only; rotation may retain the same VM over a recreated owner. */
    fun bindWorkspace(owner: ShellWorkspaceState, retainRequest: Boolean = true): Boolean {
        require(owner.currentFocus.agentId == AgentId(agentId))
        require(boundSource == null || boundSource == owner.sourceKey)
        val old = workspace
        val previous = request
        val accepted = retainRequest && acceptedBy(old, previous)
        val sameSource = old?.sourceKey == owner.sourceKey && old?.sourceGeneration == owner.sourceGeneration
        val retained = old != null && old !== owner && accepted && old.currentFocus == owner.currentFocus && sameSource
        reboundRequests = when {
            retained -> reboundRequests + checkNotNull(previous)
            old === owner && accepted -> reboundRequests
            else -> emptySet()
        }
        boundSource = owner.sourceKey
        workspace = owner
        request = if (old === owner && accepted) previous else owner.requestToken()
        runtimeConversation = owner.currentFocus.conversationId?.let { subagentConversation(agentId, it.value)?.let(::ConversationId) }
        return old != null && !retained && (old !== owner || !accepted)
    }
    private fun acceptedBy(owner: ShellWorkspaceState?, token: CurrentFocusRequest?): Boolean =
        token != null && owner?.accepts(token) == true && token.sourceGeneration == liveGeneration()
    private fun effectiveRequest(token: CurrentFocusRequest?): CurrentFocusRequest? {
        val owner = workspace ?: return null
        val current = request ?: return null
        if (!isOwnerCurrent() || current.sourceGeneration != liveGeneration()) return null
        return when {
            token != null && owner.accepts(token) -> token
            token != null && token in reboundRequests && owner.accepts(current) -> current
            else -> null
        }
    }
    fun requestToken(): CurrentFocusRequest? = request
    fun accepts(token: CurrentFocusRequest?): Boolean = if (workspace == null) !workspaceOwned else effectiveRequest(token) != null
    fun isCurrent(): Boolean = accepts(request)
    fun canPrepare(conversation: ConversationId): Boolean = workspace?.let {
        boundSource == it.sourceKey && request?.sourceGeneration == liveGeneration() &&
            (isCurrent() || runtimeConversation == conversation)
    } ?: !workspaceOwned
    /** Complete the originally captured request before hydration/send can use the resolved ID. */
    fun complete(token: CurrentFocusRequest?, conversation: ConversationId): Boolean {
        val owner = workspace
        val active = effectiveRequest(token)
        val requested = active?.exactConversation?.takeIf { subagentConversation(agentId, it.value) == conversation.value } ?: conversation
        val accepted = owner?.let { active != null && when (active.mode) {
            CurrentFocusChatMode.Exact -> it.accepts(active) && requested == active.exactConversation && it.reportAvailability(active)
            CurrentFocusChatMode.New, CurrentFocusChatMode.Resume -> it.completeFocus(active, requested, AgentId(agentId))
        } } ?: !workspaceOwned
        if (accepted) {
            runtimeConversation = if (owner == null) conversation else owner.currentFocus.conversationId
                ?.let { subagentConversation(agentId, it.value)?.let(::ConversationId) }
            request = active?.takeIf { owner?.accepts(it) == true } ?: owner?.requestToken()
            if (active?.mode != CurrentFocusChatMode.Exact) reboundRequests = emptySet()
            onSettled(owner?.currentFocus?.conversationId ?: conversation)
        }
        return accepted
    }
    fun settle(conversation: ConversationId) {
        if (!complete(request, conversation)) throw kotlinx.coroutines.CancellationException("Chat focus superseded")
    }
    internal fun savedStateHandle(): SavedStateHandle = savedStateHandle
    val projectContext = savedStateHandle.projectChatContext()
}
