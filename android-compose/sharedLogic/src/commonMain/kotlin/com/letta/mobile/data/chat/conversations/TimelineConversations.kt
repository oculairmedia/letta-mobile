package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.BranchDraft
import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.util.AgentConversationFleet
import com.letta.mobile.util.AgentConversationFleetPaging
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.util.READ_THROUGH_PAGES
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.agentConversationReader
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.data.repository.observeConversationInvalidations
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.ui.chat.session.ChatHostCommand
import com.letta.mobile.ui.chat.session.DerivedStateFlow
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a [TimelineConversations] owner holds: the list, the selection and what is on its way to the chat. */
data class TimelineConversationsState(
    /** Every agent's listed conversations, newest first. */
    val conversations: List<Conversation> = emptyList(),
    /** True once the backend has listed them; until then the list is not authoritative. */
    val loaded: Boolean = false,
    val selected: ConversationId? = null,
    /** A new conversation is being made (or an agent's found) for the chat to open. */
    val creating: Boolean = false,
    val deleting: Set<ConversationId> = emptySet(),
    /** Why the list could not load or a chat could not be opened; the chat's waiting page shows it. */
    val error: String? = null,
    /** Prompts waiting for their conversation's chat: a branch's draft, or a send from Home. */
    val drafts: Map<ConversationId, PendingDraft> = emptyMap(),
    /** Failures the open chat shows in its composer (a branch, a rename, a delete). */
    val notice: String? = null,
) {
    /** The chat is on its way: the list is loading, a chat is being made, or the selected one is opening; not after a failure. */
    val opening: Boolean get() = error == null && (!loaded || creating || selected != null)
}

/**
 * The web's conversation list owner: a host whose chat is a
 * [com.letta.mobile.ui.chat.session.TimelineChatSessionPort] per conversation. It lists the
 * roster's agents' conversations agent by agent ([AgentConversationFleet]) through the session's
 * [ChatGateway] (the App Server's own commands on ws://, the host's `admin_rpc` over Iroh), keeps
 * the selection, opens new chats and is the shell's [ShellChatPort]. Its row actions, branching and `/recompile` are the shared [management], the
 * same one desktop's chat controller binds. Build one per session in a scope that ends with it,
 * and a port for the [selectedTarget].
 */
class TimelineConversations(
    private val scope: CoroutineScope,
    private val gateway: ChatGateway,
    roster: List<Agent>,
    prefs: ConversationManagementPrefs = ConversationManagementPrefs(),
) : ShellChatPort {
    /** The agents whose conversations the list shows (and whose names title the chats); new agents list again. */
    var roster: List<Agent> = roster
        set(agents) {
            val changed = rosterNeedsConversationRefresh(field, agents)
            field = agents
            if (changed) scope.launch { refresh() }
        }

    /** Each agent's conversations, read through: the web's list has no "load more". */
    private val fleet = AgentConversationFleet(
        gateway.agentConversationReader(),
        AgentConversationFleetPaging(eagerPagesPerAgent = READ_THROUGH_PAGES),
    )

    private val mutableState = MutableStateFlow(TimelineConversationsState())
    val state: StateFlow<TimelineConversationsState> = mutableState.asStateFlow()

    /** Rename, pin, archive, delete, fork / edit / rerun and `/recompile`, shared with desktop. */
    val management = ConversationManagement(scope, ListHost(), prefs)

    /** Who waits for the chat being made (or found): every caller gets it, not only the first. */
    private val waitingForChat = mutableListOf<(ConversationId) -> Unit>()

    init {
        scope.launch { refresh() }
        scope.launch {
            observeConversationInvalidations(gateway.conversationChanges) { dirty -> refreshChanged(dirty) }
        }
    }

    private suspend fun refreshChanged(dirty: Map<ConversationId, AgentId?>) {
        runCatchingCancellable {
            fleet.refreshChanged(dirty) { gateway.getConversation(it.value) } ?: fleet.refresh(roster)
        }.onSuccess { listed ->
            mutableState.update { current ->
                current.copy(conversations = listed.keeping(current.conversations.filter { it.id == current.selected }), loaded = true, error = null)
            }
        }.onFailure { failure -> mutableState.update { it.copy(error = failure.message ?: LIST_FAILED) } }
    }

    /**
     * Lists the roster's conversations again; a failure keeps the last list and says why. A
     * conversation the list does not carry yet (the open one, made while it loaded) stays. With no
     * roster yet there is nothing to list, and the list stays unloaded.
     */
    suspend fun refresh() {
        // With no roster yet the list stays unloaded; a roster emptied after loading lists nothing.
        if (roster.isEmpty() && !mutableState.value.loaded) return
        runCatchingCancellable { fleet.refresh(roster) }
            .onSuccess { listed ->
                mutableState.update { current ->
                    val selected = current.conversations.filter { it.id == current.selected }
                    current.copy(conversations = listed.keeping(selected), loaded = true, error = null)
                }
            }
            .onFailure { failure -> mutableState.update { it.copy(error = failure.message ?: LIST_FAILED) } }
    }

    /** The chat the page opens now: the selected conversation, titled by its agent. */
    fun selectedTarget(): TimelineChatTarget? = mutableState.value.selectedRecord()?.let { conversation ->
        TimelineChatTarget(
            agentId = conversation.agentId.value,
            agentName = conversation.agentName?.takeIf(String::isNotBlank) ?: roster.nameOf(conversation.agentId),
            conversationId = conversation.id.value,
        )
    }

    override fun conversations(): List<ShellConversationRef> =
        mutableState.value.conversations.filterNot { it.archived == true }.map { it.shellRef() }

    override val selectedConversation: ConversationId? get() = mutableState.value.selected

    override fun selectConversation(conversation: ConversationId) {
        mutableState.update { it.copy(selected = conversation) }
    }

    /**
     * [agent]'s chat when the shell finds none listed (an agent the roster did not carry yet, or a
     * list still loading): its newest active conversation on the server, else a new one; [onCreated] gets it either way.
     * When the server cannot be asked, nothing is made: that could duplicate a chat it already has.
     */
    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) =
        openChat(agent, findExisting = true, onCreated)

    /** A new chat with [agent], or with the selected conversation's agent; with neither, nothing to start. */
    override fun startNewChat(agent: AgentId?) {
        val target = agent ?: mutableState.value.selectedRecord()?.agentId ?: return
        openChat(target, findExisting = false) {}
    }

    /**
     * Opens the first chat once the list has loaded and nothing is selected: [routed] (a reload or
     * a link) while it is listed, else [focused]'s chat, made when [createIfNone] (the chat is the page).
     */
    fun openInitial(routed: ConversationId?, focused: AgentId?, createIfNone: Boolean) {
        val current = mutableState.value
        if (!current.awaitsFirstChat) return
        val first = current.listed(routed) ?: focused?.let(current::activeChatOf)
        if (first != null) {
            selectConversation(first)
            return
        }
        if (focused != null && current.mayCreate(createIfNone)) createConversationForAgent(focused)
    }

    override fun send(conversation: ConversationId, text: String) {
        mutableState.update { it.copy(selected = conversation).withDraft(conversation, PendingDraft(BranchDraft(text), sendNow = true)) }
    }

    override fun stageComposerText(text: String) {
        val selected = mutableState.value.selected ?: return
        mutableState.update { it.withDraft(selected, PendingDraft(BranchDraft(text))) }
    }

    /**
     * Hands [conversation]'s chat what is waiting for it while it is open: a pending prompt goes
     * into its composer (and is sent, for a rerun or a send from Home), and a failure into its
     * composer's error. Run it in the chat's own scope.
     */
    suspend fun deliverTo(conversation: ConversationId, actions: ChatActions): Unit = coroutineScope {
        launch {
            mutableState.map { it.drafts[conversation] }.distinctUntilChanged().filterNotNull().collect { pending ->
                mutableState.update { it.copy(drafts = it.drafts - conversation) }
                actions.applyDraft(pending)
            }
        }
        mutableState.map { it.notice }.distinctUntilChanged().filterNotNull().collect { notice ->
            mutableState.update { it.copy(notice = null) }
            actions.reportComposerError(notice)
        }
    }

    /** One chat in the making at a time; a second ask waits for the same one. */
    private fun openChat(agent: AgentId, findExisting: Boolean, onCreated: (ConversationId) -> Unit) {
        waitingForChat += onCreated
        if (mutableState.value.creating) return
        mutableState.update { it.copy(creating = true, error = null) }
        scope.launch {
            val opened = runCatchingCancellable { (if (findExisting) existingChat(agent) else null) ?: create(agent) }
                .onFailure { failure -> mutableState.update { it.copy(error = failure.message ?: CREATE_FAILED) } }
                .getOrNull()
            val waiting = waitingForChat.toList()
            waitingForChat.clear()
            mutableState.update { it.copy(creating = false) }
            if (opened != null) waiting.forEach { it(opened) }
        }
    }

    /** [agent]'s newest active conversation on the server, which joins the list and opens; null when it has none. */
    private suspend fun existingChat(agent: AgentId): ConversationId? {
        val own = gateway.listAgentConversations(AgentConversationPage(agent))
            .filter { it.agentId == agent }
            .visibleConversationsInLists()
        mutableState.update { it.copy(conversations = it.conversations.keeping(own)) }
        val newest = own.firstOrNull { it.archived != true } ?: return null
        selectConversation(newest.id)
        return newest.id
    }

    private suspend fun create(agent: AgentId): ConversationId {
        val extras = gateway as? ChatGatewayExtras ?: error(NO_CREATE)
        val created = extras.createConversation(agent.value)
        opened(created, draft = null)
        return created.id
    }

    /** A conversation the owner made (a new chat or a branch) joins the top of the list and opens. */
    private fun opened(conversation: Conversation, draft: PendingDraft?) {
        mutableState.update { current ->
            val listed = listOf(conversation) + current.conversations.filterNot { it.id == conversation.id }
            val next = current.copy(conversations = listed, selected = conversation.id)
            if (draft == null) next else next.withDraft(conversation.id, draft)
        }
    }

    /** The list as the shared [management] reads and writes it. */
    private inner class ListHost : ConversationListHost {
        override val gateway: ChatGateway get() = this@TimelineConversations.gateway
        override val isClosed: Boolean get() = false
        override val gatewayChanges: Flow<Unit> = flowOf(Unit)

        override fun origin(): ConversationOrigin? =
            mutableState.value.selectedRecord()?.let { ConversationOrigin(it.id, it.agentId) }

        override fun listedTitle(conversation: ConversationId): String? =
            mutableState.value.record(conversation)?.let { it.summary.orEmpty() }

        override fun showTitle(conversation: ConversationId, title: ConversationSummary, onlyIf: ConversationSummary?) {
            mutableState.update { current ->
                current.edit(conversation) { row -> if (row.showsTitle(onlyIf)) row.copy(summary = title.value) else row }
            }
        }

        override fun showArchived(conversation: ConversationId, archived: Boolean) {
            mutableState.update { current -> current.edit(conversation) { it.copy(archived = archived) } }
        }

        override fun beginDelete(conversation: ConversationId): Boolean {
            if (conversation in mutableState.value.deleting) return false
            mutableState.update { it.copy(deleting = it.deleting + conversation) }
            return true
        }

        override suspend fun endDelete(conversation: ConversationId, behavior: ConversationDeleteBehavior?) {
            mutableState.update { current ->
                if (behavior == null) current.copy(deleting = current.deleting - conversation) else current.without(conversation)
            }
        }

        override suspend fun openBranch(branch: OpenedBranch) = opened(branch.conversation, branch.draft)

        override fun showError(message: String) {
            mutableState.update { it.copy(notice = message) }
        }
    }

    private companion object {
        const val LIST_FAILED = "Could not list the conversations"
        const val CREATE_FAILED = "Could not start a new conversation"
        const val NO_CREATE = "This backend can't start a new conversation"
    }
}

private fun TimelineConversationsState.withDraft(conversation: ConversationId, draft: PendingDraft) =
    copy(drafts = drafts + (conversation to draft))

/** A prompt into the composer; sent at once for a rerun or a send, text alone straight from the draft-free send. */
private fun ChatActions.applyDraft(pending: PendingDraft) {
    val draft = pending.draft
    if (pending.sendNow && draft.images.isEmpty()) {
        sendText(draft.text)
        return
    }
    updateComposerText(draft.text)
    draft.images.forEach(::attachImage)
    if (pending.sendNow) send()
}

/** The shell's view of a conversation: whose it is and its newest activity. */
private fun Conversation.shellRef() = ShellConversationRef(id, agentId, updatedAtLabel = lastMessageAt ?: updatedAt ?: createdAt.orEmpty())

/** With no [expected] title, any; else only while the row still shows it. */
private fun Conversation.showsTitle(expected: ConversationSummary?): Boolean = expected == null || summary.orEmpty() == expected.value

private fun TimelineConversationsState.selectedRecord(): Conversation? = record(selected)

private fun TimelineConversationsState.record(conversation: ConversationId?): Conversation? =
    conversation?.let { id -> conversations.firstOrNull { it.id == id } }

private fun TimelineConversationsState.edit(conversation: ConversationId, change: (Conversation) -> Conversation) =
    copy(conversations = conversations.map { if (it.id == conversation) change(it) else it })

/** The list without [conversation]; when it was the selected one, its agent's next active conversation is. */
private fun TimelineConversationsState.without(conversation: ConversationId): TimelineConversationsState {
    val remaining = conversations.filterNot { it.id == conversation }
    val next = if (selected == conversation) record(conversation)?.agentId?.let { agent -> remaining.activeChatOf(agent) } else selected
    return copy(conversations = remaining, selected = next, deleting = deleting - conversation)
}

/** The roster's name for [agent], for the chat's title. */
private fun List<Agent>.nameOf(agent: AgentId): String = firstOrNull { it.id == agent }?.name.orEmpty()

/** Names and ordering enrich existing rows; first arrival and visible membership settle the list. */
private fun rosterNeedsConversationRefresh(previous: List<Agent>, next: List<Agent>): Boolean {
    val firstArrival = previous.isEmpty() && next.isNotEmpty()
    val membershipChanged = previous.visibleInLists().mapTo(mutableSetOf()) { it.id } !=
        next.visibleInLists().mapTo(mutableSetOf()) { it.id }
    return firstArrival || membershipChanged
}

/** This list, then the [others] it does not carry (newest first within each). */
private fun List<Conversation>.keeping(others: List<Conversation>): List<Conversation> {
    val ids = mapTo(mutableSetOf()) { it.id }
    return this + others.filterNot { it.id in ids }
}

/** Loaded, with nothing selected and nothing being made: the first chat is still to open. */
private val TimelineConversationsState.awaitsFirstChat: Boolean
    get() = loaded && isIdle

private val TimelineConversationsState.isIdle: Boolean get() = selected == null && !creating

/** [conversation] while the list carries it. */
private fun TimelineConversationsState.listed(conversation: ConversationId?): ConversationId? = record(conversation)?.id

/** [agent]'s newest active conversation in the list. */
private fun TimelineConversationsState.activeChatOf(agent: AgentId): ConversationId? = conversations.activeChatOf(agent)

private fun List<Conversation>.activeChatOf(agent: AgentId): ConversationId? =
    firstOrNull { it.agentId == agent && it.archived != true }?.id

/** A first chat may be made when the host asks for one and the last attempt did not fail. */
private fun TimelineConversationsState.mayCreate(wanted: Boolean): Boolean = wanted && error == null

/** What a timeline chat port takes from the shared [owner]: branching and `/recompile`, and the host's `/` [commands]. */
class TimelineConversationChat(val owner: TimelineConversations, val commands: List<ChatHostCommand>)

/** These options with [chat]'s branching, `/recompile` and `/` palette; with no [chat], unchanged. */
fun TimelineChatSessionOptions.withConversations(chat: TimelineConversationChat?): TimelineChatSessionOptions {
    chat ?: return this
    val inputs = composerHost ?: MutableStateFlow(ChatComposerHostInputs()).asStateFlow()
    return copy(
        branching = chat.owner.management.branches,
        recompile = chat.owner.management.recompile,
        composerHost = DerivedStateFlow(inputs.map { it.copy(commands = chat.commands) }) { inputs.value.copy(commands = chat.commands) },
    )
}
