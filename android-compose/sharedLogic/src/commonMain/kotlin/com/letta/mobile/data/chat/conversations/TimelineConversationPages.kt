package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.lanes.ConversationLane
import com.letta.mobile.data.lanes.ConversationPurpose
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.util.runCatchingCancellable
import com.letta.mobile.ui.chat.session.ChatActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** [agent]'s newest active conversation on the server, which joins the list and opens; null when it has none. */
internal suspend fun ChatGateway.existingChat(
    agent: AgentId,
    state: MutableStateFlow<TimelineConversationsState>,
    select: (ConversationId) -> Unit,
): ConversationId? {
    var after: ConversationId? = null
    while (true) {
        val request = AgentConversationPage(agent, after = after)
        val page = listAgentConversations(request)
        val newest = state.keepOwnPage(agent, page)
        if (newest != null) return newest.id.also(select)
        // A full page of archives is not proof that the agent has no active chat.
        after = nextCursor(page, request, after) ?: return null
    }
}

/** Lists [agent]'s visible rows of [page]; its newest active one, or null. */
private fun MutableStateFlow<TimelineConversationsState>.keepOwnPage(agent: AgentId, page: List<Conversation>): Conversation? {
    val own = page.filter { it.agentId == agent }.visibleConversationsInLists()
    update { it.copy(conversations = it.conversations.keeping(own)) }
    return own.firstOrNull { it.archived != true }
}

/** The cursor after [page], or null when [page] was the last one. */
private fun nextCursor(page: List<Conversation>, request: AgentConversationPage, after: ConversationId?): ConversationId? {
    if (page.size < request.limit) return null
    val next = page.lastOrNull()?.id
    check(next != after) { "Conversation cursor did not advance" }
    return next
}

internal fun TimelineConversationsState.selectedRecord(): Conversation? = record(selected)

internal fun TimelineConversationsState.record(conversation: ConversationId?): Conversation? =
    conversation?.let { id -> conversations.firstOrNull { it.id == id } }

internal fun TimelineConversationsState.edit(conversation: ConversationId, change: (Conversation) -> Conversation) =
    copy(conversations = conversations.map { if (it.id == conversation) change(it) else it })

/** The list without [conversation]; its selected identity remains until the workspace changes it. */
internal fun TimelineConversationsState.without(conversation: ConversationId): TimelineConversationsState {
    val remaining = conversations.filterNot { it.id == conversation }
    return copy(conversations = remaining, deleting = deleting - conversation)
}

/** This list, then the [others] it does not carry (newest first within each). */
internal fun List<Conversation>.keeping(others: List<Conversation>): List<Conversation> {
    val ids = mapTo(mutableSetOf()) { it.id }
    return this + others.filterNot { it.id in ids }
}

/** [agent]'s home conversation, else its newest active human conversation in the list. */
internal fun TimelineConversationsState.activeChatOf(agent: AgentId): ConversationId? = conversations.activeChatOf(agent)

/** Inbox and work conversations are never a human fallback; the main agent's home stays the anchor. */
internal fun List<Conversation>.activeChatOf(agent: AgentId): ConversationId? {
    val active = filter { it.agentId == agent && it.archived != true }
    return (active.firstOrNull { ConversationLane.fromTags(it.tags).purpose == ConversationPurpose.HOME }
        ?: active.firstOrNull { ConversationLane.fromTags(it.tags).isHumanFallback })?.id
}

/** A first chat may be made when the host asks for one and the last attempt did not fail. */
internal fun TimelineConversationsState.mayCreate(wanted: Boolean): Boolean = wanted && error == null

/** A conversation the owner made (a new chat or a branch) joins the top of the list and opens. */
internal fun MutableStateFlow<TimelineConversationsState>.opened(conversation: Conversation, draft: PendingDraft?) {
    update { current ->
        val listed = listOf(conversation) + current.conversations.filterNot { it.id == conversation.id }
        val next = current.copy(conversations = listed, selected = conversation.id)
        if (draft == null) next else next.withDraft(conversation.id, draft)
    }
}

/** A prompt into the composer; sent at once for a rerun or a send, text alone straight from the draft-free send. */
internal fun ChatActions.applyDraft(pending: PendingDraft) {
    val draft = pending.draft
    if (pending.sendNow && draft.images.isEmpty()) {
        sendText(draft.text)
        return
    }
    updateComposerText(draft.text)
    draft.images.forEach(::attachImage)
    if (pending.sendNow) send()
}

/** [conversation]'s row: the listed one, else fetched once and listed; null (with the error shown) when the fetch fails. */
internal suspend fun MutableStateFlow<TimelineConversationsState>.readSelectedConversation(
    gateway: ChatGateway,
    conversation: ConversationId,
): Conversation? = value.record(conversation) ?: runCatchingCancellable {
    gateway.getConversation(conversation.value)
}.onFailure { failure -> update { it.failedToRead(conversation, failure) } }
    .getOrNull()?.also { fetched -> update { it.copy(conversations = it.conversations.keeping(listOf(fetched))) } }

/** Shows [failure] only while [conversation] is still the selected one. */
private fun TimelineConversationsState.failedToRead(conversation: ConversationId, failure: Throwable): TimelineConversationsState {
    if (selected != conversation) return this
    return copy(error = failure.message ?: "Could not list the conversations")
}
