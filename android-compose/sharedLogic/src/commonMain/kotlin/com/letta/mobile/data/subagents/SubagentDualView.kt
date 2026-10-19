package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentKind
import com.letta.mobile.runtime.ToolCallId
import kotlin.jvm.JvmInline

/**
 *  (G39) / the subagent dual view. A subagent's own conversation opens
 * beside its parent's (one pane each on a wide window, one at a time on a compact one), read-only:
 * the reader watches the child work and replies in the parent. Several can be open, one is shown.
 *
 * Pure state and its transitions; the shared UI holds one per parent conversation and every client
 * binds the same openers to it (the roster, the rings, the timeline's dispatch and report rows).
 */
data class SubagentView(
    val agentId: AgentId,
    val conversationId: ConversationId,
    /** The dispatch's description when known; the tab falls back to the kind and a short agent id. */
    val title: String? = null,
    val kind: SubagentViewKind = SubagentViewKind.SUBAGENT,
) {
    val key: SubagentViewKey get() = SubagentViewKey("${agentId.value}/${conversationId.value}")

    /** The agent id's first characters, for a tab without a title. */
    val shortAgentId: String
        get() = IdFormats.stripAgentPrefix(agentId).take(SHORT_AGENT_ID_LENGTH)
}

/** One open subagent view: its child agent and conversation. */
@JvmInline
value class SubagentViewKey(val value: String)

/** A dream (sleep-time reflection) run is labelled apart from the subagents the parent dispatched. */
enum class SubagentViewKind { SUBAGENT, DREAM }

/** Which side has the reader: on a compact window the only side shown. */
enum class DualViewFocus { PARENT, SUBAGENT }

data class SubagentDualViewState(
    val views: List<SubagentView> = emptyList(),
    val activeKey: SubagentViewKey? = null,
    val focus: DualViewFocus = DualViewFocus.PARENT,
) {
    val active: SubagentView? get() = views.firstOrNull { it.key == activeKey }
    val isOpen: Boolean get() = views.isNotEmpty()
    val showsSubagent: Boolean get() = focus == DualViewFocus.SUBAGENT && active != null

    /** Opens [view] (or shows it again when it is open already) and moves the reader to it. */
    fun open(view: SubagentView): SubagentDualViewState {
        val listed = if (views.any { it.key == view.key }) views else (views + view).takeLast(MAX_OPEN_SUBAGENT_VIEWS)
        return copy(views = listed, activeKey = view.key, focus = DualViewFocus.SUBAGENT)
    }

    fun select(key: SubagentViewKey): SubagentDualViewState =
        if (views.none { it.key == key }) this else copy(activeKey = key, focus = DualViewFocus.SUBAGENT)

    /** Back to the parent; the subagent views stay open to return to. */
    fun showParent(): SubagentDualViewState = copy(focus = DualViewFocus.PARENT)

    /** Closes one view; the active one hands over to its neighbour, and the last one returns to the parent. */
    fun close(key: SubagentViewKey): SubagentDualViewState {
        val index = views.indexOfFirst { it.key == key }
        if (index < 0) return this
        val remaining = views.filterNot { it.key == key }
        if (remaining.isEmpty()) return Closed
        val nextActive = if (activeKey == key) remaining[index.coerceAtMost(remaining.lastIndex)].key else activeKey
        return copy(views = remaining, activeKey = nextActive)
    }

    companion object {
        val Closed = SubagentDualViewState()
    }
}

/** Open views are bounded: the oldest closes when one more opens. */
const val MAX_OPEN_SUBAGENT_VIEWS: Int = 6

private const val SHORT_AGENT_ID_LENGTH = 8

/**
 * The view for a child agent's conversation, or null when there is none to open: an unknown agent,
 * an external coding agent (not a Letta agent), or a conversation not known yet (letta-code names
 * it when the child resolves; navigation never guesses). The registry's bare `default` is mapped to
 * the addressable `conv-default-<agentId>` form every transport routes.
 */
fun subagentViewOf(agentId: AgentId?, conversationId: ConversationId?, label: SubagentViewLabel): SubagentView? {
    val agent = agentId?.takeIf { it.value.isNotBlank() } ?: return null
    if (label.identity.isExternal || subagentIdentityOf(null, listOf(agent.value)).isExternal) return null
    val conversation = conversationId?.value?.takeIf { it.isNotBlank() } ?: return null
    return SubagentView(agent, addressable(agent, conversation), label.title?.takeIf { it.isNotBlank() }, label.kind)
}

private fun addressable(agent: AgentId, conversation: String): ConversationId = ConversationId(
    if (conversation == IdFormats.DEFAULT_CONVERSATION_ID) IdFormats.defaultConversationIdFor(agent.value) else conversation,
)

/** What a timeline row knows about the subagent its dispatch started. */
data class SubagentDispatchTarget(
    val dispatch: ToolCallId,
    /** The child agent the row correlated, when it did. */
    val childAgent: AgentId? = null,
    val description: String? = null,
)

/**
 * The view a timeline dispatch (or its report) opens. The registry is the authority where this
 * client has one: the dispatch's entry names the child's agent and conversation, and while it
 * names no conversation (running; letta-code names it when the child resolves) there is nothing to
 * open. A client without a registry entry for the dispatch (the web has no registry) opens the
 * child agent the row knows in its default conversation, where letta-code runs a subagent.
 */
fun List<SubagentEntry>.subagentViewForDispatch(target: SubagentDispatchTarget): SubagentView? {
    val entry = firstOrNull { it.toolCallId == target.dispatch.value || it.taskId == target.dispatch.value }
    if (entry == null) {
        val label = SubagentViewLabel(title = target.description)
        return subagentViewOf(target.childAgent, ConversationId(IdFormats.DEFAULT_CONVERSATION_ID), label)
    }
    return subagentViewOf(
        agentId = entry.subagentAgentId?.let(::AgentId) ?: target.childAgent,
        conversationId = entry.subagentConversationId?.let(::ConversationId),
        label = SubagentViewLabel.of(
            title = target.description ?: entry.description,
            subagentType = entry.subagentType,
            ids = listOf(entry.subagentAgentId, entry.toolCallId),
        ),
    )
}

/** How a view is labelled, and whose work it is. */
data class SubagentViewLabel(
    val title: String? = null,
    val kind: SubagentViewKind = SubagentViewKind.SUBAGENT,
    val identity: SubagentIdentity = SubagentIdentity.LETTA,
) {
    companion object {
        /** The label for a run of [subagentType] described as [title]; [ids] are the run's known ids. */
        fun of(title: String?, subagentType: String?, ids: List<String?> = emptyList()): SubagentViewLabel =
            SubagentViewLabel(title = title, kind = viewKindOf(subagentType), identity = subagentIdentityOf(subagentType, ids))

        private fun viewKindOf(subagentType: String?): SubagentViewKind =
            if (SubagentKind.fromWire(subagentType).isDream) SubagentViewKind.DREAM else SubagentViewKind.SUBAGENT
    }
}

/** This roster row's conversation as a view, once its child agent and conversation are known. */
fun SubagentRosterItem.toSubagentView(): SubagentView? = subagentViewOf(
    agentId = childAgentId?.let(::AgentId),
    conversationId = childConversationId?.let(::ConversationId),
    label = SubagentViewLabel.of(title, typeLabel).copy(identity = identity),
)
