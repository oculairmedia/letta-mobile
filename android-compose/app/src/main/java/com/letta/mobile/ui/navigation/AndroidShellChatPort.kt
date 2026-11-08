package com.letta.mobile.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.session.backendConnectionKey
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.CurrentFocusRequest
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellWorkspaceState

/** Android effects carry the shared owner's request; an Opening never owns the current selection. */
class AndroidShellChatPort(
    private val workspace: ShellWorkspaceState,
    private val fleet: () -> List<Conversation>,
    @Suppress("UNUSED_PARAMETER") roster: () -> List<AgentId>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCurrentOwner: () -> Boolean = { true },
    private val onOpening: (Opening) -> Unit = {},
) : ShellChatPort {
    /** A captured load/create request and its one-shot payload; neither is saved or replayed. */
    class Opening internal constructor(
        val agent: AgentId,
        val conversation: ConversationId?,
        val request: CurrentFocusRequest,
        internal val workspace: ShellWorkspaceState,
        val fresh: Long? = null,
        val firstMessage: String? = null,
        private var onCreated: ((ConversationId) -> Unit)? = null,
    ) {
        internal fun created(conversation: ConversationId) {
            val callback = onCreated
            onCreated = null
            callback?.invoke(conversation)
        }
    }

    var opening: Opening? by mutableStateOf(null)
        private set

    /** Text for the current composer's explicit hand-off, outside saved navigation. */
    val draft = mutableStateOf<String?>(null)
    var pendingShare: String? by mutableStateOf(null)

    override fun conversations(): List<ShellConversationRef> = fleet().map {
        ShellConversationRef(it.id, it.agentId, it.lastMessageAt ?: it.updatedAt ?: it.createdAt.orEmpty(), tags = it.tags)
    }

    override val selectedConversation: ConversationId? get() = workspace.currentFocus.conversationId

    override fun selectConversation(conversation: ConversationId) {
        val agent = agentOf(fleet(), conversation) ?: return
        if (!select(agent, conversation, CurrentFocusChatMode.Exact)) return
        opening = Opening(agent, conversation, workspace.requestToken(), workspace).also(onOpening)
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) {
        if (!select(agent, null, CurrentFocusChatMode.New)) return
        opening = Opening(agent, null, workspace.requestToken(), workspace, fresh = clock(), onCreated = onCreated).also(onOpening)
    }

    override fun startNewChat(agent: AgentId?) {
        val target = agent ?: workspace.currentFocus.agentId ?: return
        if (!select(target, null, CurrentFocusChatMode.New)) return
        opening = Opening(target, null, workspace.requestToken(), workspace, fresh = clock()).also(onOpening)
    }

    override fun send(conversation: ConversationId, text: String) {
        val agent = agentOf(fleet(), conversation) ?: return
        if (!select(agent, conversation, CurrentFocusChatMode.Exact)) return
        opening = Opening(agent, conversation, workspace.requestToken(), workspace, firstMessage = text).also(onOpening)
    }

    /** A share waits for an explicit agent choice. */
    fun deliverShare(agent: AgentId): Boolean {
        val text = pendingShare ?: return false
        if (!isCurrentOwner() || !workspace.selectFocus(CurrentFocus("chat", agent, chatMode = CurrentFocusChatMode.New))) return false
        pendingShare = null
        opening = Opening(agent, null, workspace.requestToken(), workspace, fresh = clock(), firstMessage = text).also(onOpening)
        return true
    }

    override fun stageComposerText(text: String) { if (isCurrentOwner()) draft.value = text }

    /** Reject the old page before its callback can commit a selection or trigger a send. */
    fun conversationShown(shown: Opening, conversation: ConversationId) {
        if (!current(shown) || conversation.value.isBlank()) return
        if (!workspace.accepts(shown.request) && workspace.currentFocus.conversationId != conversation) return
        // The navigator's compatibility create callback can commit and synchronously send. Let it
        // use the originally captured token, then confirm settlement without republishing Opening.
        shown.created(conversation)
        if (shown.request.mode != CurrentFocusChatMode.Exact && workspace.accepts(shown.request)) {
            workspace.completeFocus(shown.request, conversation, shown.agent)
        }
    }

    /** Recreated pages may adopt current identity; old pages cannot reacquire selection authority. */
    fun openingFor(agent: AgentId, conversation: ConversationId?): Opening? = opening?.takeIf {
        current(it) && it.agent == agent && (conversation == it.conversation || conversation == workspace.currentFocus.conversationId)
    }

    fun adopt(agent: AgentId, conversation: ConversationId?): Opening = Opening(agent, conversation, workspace.requestToken(), workspace)

    /** Explicit chat navigation commits the complete target before the page starts its work. */
    fun open(agent: AgentId, conversation: ConversationId) {
        if (!isCurrentOwner() || !workspace.selectFocus(CurrentFocus("chat", agent, conversation, CurrentFocusChatMode.Exact))) return
        opening = Opening(agent, conversation, workspace.requestToken(), workspace).also(onOpening)
    }

    /** Invalidate effects and target-bound drafts on a backend change. */
    fun sourceChanged() {
        opening = null
        draft.value = null
    }

    private fun select(agent: AgentId, conversation: ConversationId?, mode: CurrentFocusChatMode): Boolean {
        val focus = CurrentFocus("chat", agent, conversation, mode)
        return isCurrentOwner() && (workspace.currentFocus == focus || workspace.selectFocus(focus))
    }

    private fun current(shown: Opening): Boolean {
        if (!isCurrentOwner() || shown.workspace !== workspace) return false
        return shown.request.sourceKey == workspace.sourceKey && shown.request.sourceGeneration == workspace.sourceGeneration &&
            shown.request.focusRevision == workspace.focusRevision && shown.agent == workspace.currentFocus.agentId
    }

    companion object {
        private fun agentOf(fleet: List<Conversation>, conversation: ConversationId): AgentId? =
            fleet.filter { it.id == conversation }.map { it.agentId }.distinct().singleOrNull()

        /** Opaque host namespace bound to the graph's captured endpoint/principal configuration. */
        internal fun sourceKey(
            graph: com.letta.mobile.data.session.SessionGraph,
            config: com.letta.mobile.data.model.LettaConfig? = graph.capturedConfig,
        ): String {
            val key = config?.backendConnectionKey()
            val binding = listOf(graph.backendDescriptor.backendId.value, config?.id, key?.mode?.name, key?.serverUrl, key?.accessToken)
                .joinToString("") { "${it?.length ?: -1}:$it" }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(binding.toByteArray(Charsets.UTF_8))
            return "android-focus-v1:" + digest.joinToString("") { "%02x".format(it) }
        }
    }

}
