package com.letta.mobile.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellWorkspaceState

/**
 * Android's chat as the shared shell's navigation drives it (letta-mobile-ckmub.2). Android's chat view
 * model resolves or creates its conversation itself, so the shell's verbs set what the chat page opens
 * next ([opening]): a conversation, or a new chat (with a first message) whose conversation the page
 * reports through [conversationShown] once it exists.
 */
class AndroidShellChatPort(
    private val workspace: ShellWorkspaceState,
    private val fleet: () -> List<Conversation>,
    private val roster: () -> List<AgentId>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ShellChatPort {
    /**
     * One chat for the page: [agent]'s [conversation]; a null conversation is a new chat keyed by [fresh].
     * [resolved] is the conversation the page settled on, so a new chat keeps its page once it exists.
     */
    class Opening(
        val agent: AgentId,
        val conversation: ConversationId?,
        val fresh: Long? = null,
        val firstMessage: String? = null,
        private var onCreated: ((ConversationId) -> Unit)? = null,
    ) {
        var resolved: ConversationId? = conversation
            private set

        fun shows(conversation: ConversationId?): Boolean =
            conversation == null || conversation == this.conversation || conversation == resolved

        internal fun settle(conversation: ConversationId) {
            resolved = conversation
            onCreated?.invoke(conversation)
            onCreated = null
        }
    }

    /** What the chat page shows; null opens the focused agent's chat. */
    var opening: Opening? by mutableStateOf(null)
        private set

    /** Text for the open chat's composer, which the page takes (sets back to null). */
    val draft = mutableStateOf<String?>(null)

    override fun conversations(): List<ShellConversationRef> = fleet().map {
        ShellConversationRef(it.id, it.agentId, it.lastMessageAt ?: it.updatedAt ?: it.createdAt.orEmpty())
    }

    override val selectedConversation: ConversationId? get() = workspace.selectedConversationId

    override fun selectConversation(conversation: ConversationId) {
        val agent = agentOf(conversation) ?: return
        show(Opening(agent, conversation))
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) =
        show(Opening(agent, conversation = null, fresh = clock(), onCreated = onCreated))

    override fun startNewChat(agent: AgentId?) {
        val target = agent ?: workspace.focusedAgentId ?: roster().firstOrNull() ?: return
        show(Opening(target, conversation = null, fresh = clock()))
    }

    /** The chat opens on [conversation] and sends [text] once it has loaded. */
    override fun send(conversation: ConversationId, text: String) {
        val agent = agentOf(conversation) ?: return
        show(Opening(agent, conversation, fresh = null, firstMessage = text))
    }

    /** Text shared into the app, waiting for the person to pick its agent. */
    var pendingShare: String? by mutableStateOf(null)

    /** Starts a new chat with [agent] whose first message is the waiting share; false when none waits. */
    fun deliverShare(agent: AgentId): Boolean {
        val text = pendingShare ?: return false
        pendingShare = null
        show(Opening(agent, conversation = null, fresh = clock(), firstMessage = text))
        workspace.showChat()
        return true
    }

    override fun stageComposerText(text: String) {
        draft.value = text
    }

    /** The page showing [shown] settled on [conversation] (a new chat's now exists); the selection follows it. */
    fun conversationShown(shown: Opening, conversation: ConversationId) {
        shown.settle(conversation)
        opening = shown
        workspace.focusedAgentId = shown.agent
        workspace.conversationShown(conversation, shown.agent)
    }

    /** The verbs' chat when it is [agent]'s [conversation]; null when the selection came another way (a reload, a link). */
    fun openingFor(agent: AgentId, conversation: ConversationId?): Opening? =
        opening?.takeIf { it.agent == agent && it.shows(conversation) }

    /** A chat for a selection no verb chose; without a conversation the chat resumes the agent's latest. */
    fun adopt(agent: AgentId, conversation: ConversationId?): Opening = Opening(agent, conversation)

    /** [agent]'s [conversation], from the chat itself (a fork, a subagent's transcript, another agent's label). */
    fun open(agent: AgentId, conversation: ConversationId) = show(Opening(agent, conversation))

    private fun show(next: Opening) {
        opening = next
        workspace.focusedAgentId = next.agent
        workspace.conversationShown(next.conversation, next.agent)
    }

    private fun agentOf(conversation: ConversationId): AgentId? =
        fleet().firstOrNull { it.id == conversation }?.agentId ?: workspace.focusedAgentId
}
