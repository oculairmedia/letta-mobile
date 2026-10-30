package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.visibleConversationsInLists
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.ui.chat.session.ChatActions
import kotlinx.coroutines.flow.update

/** A routed conversation outside the recents page, fetched once; null when listed, in legacy mode or already failed. */
internal fun TimelineConversationsState.unlistedRoute(
    routed: ConversationId?,
    failed: ConversationId?,
    legacyMode: Boolean,
): ConversationId? {
    if (legacyMode) return null
    val route = routed?.takeIf { it != failed } ?: return null
    return route.takeIf { record(it) == null }
}

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

/** The list without [conversation]; when it was the selected one, its agent's next active conversation is. */
internal fun TimelineConversationsState.without(conversation: ConversationId): TimelineConversationsState {
    val remaining = conversations.filterNot { it.id == conversation }
    val next = if (selected == conversation) record(conversation)?.agentId?.let { agent -> remaining.activeChatOf(agent) } else selected
    return copy(conversations = remaining, selected = next, deleting = deleting - conversation)
}

/** This list, then the [others] it does not carry (newest first within each). */
internal fun List<Conversation>.keeping(others: List<Conversation>): List<Conversation> {
    val ids = mapTo(mutableSetOf()) { it.id }
    return this + others.filterNot { it.id in ids }
}

/** [conversation] while the list carries it. */
internal fun TimelineConversationsState.listed(conversation: ConversationId?): ConversationId? = record(conversation)?.id

/** [agent]'s newest active conversation in the list. */
internal fun TimelineConversationsState.activeChatOf(agent: AgentId): ConversationId? = conversations.activeChatOf(agent)

internal fun List<Conversation>.activeChatOf(agent: AgentId): ConversationId? =
    firstOrNull { it.agentId == agent && it.archived != true }?.id

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
