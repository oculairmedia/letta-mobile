package com.letta.mobile.data.shell

import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.focus.CurrentFocus
import com.letta.mobile.data.shell.focus.CurrentFocusChatMode
import com.letta.mobile.data.shell.focus.CurrentFocusSnapshot

/** Availability is evidence about a requested target, never a replacement selection. */
enum class FocusAvailability {
    /** No conclusive evidence, including an empty or incomplete roster. */
    Unknown,
    /** The requested object has been found in the bound source. */
    Available,
    /** Trustworthy not-found/deletion evidence; requested identity still remains selected. */
    Unavailable,
}

/** Runtime identity of a request. Capture before starting work; never save or recreate this value. */
class CurrentFocusRequest internal constructor(
    /** Trusted namespace captured when the effect began. */
    val sourceKey: String?,
    /** Connection generation captured before dispatch. */
    val sourceGeneration: Long,
    /** Selection revision captured before dispatch. */
    val focusRevision: Long,
    /** Requested owner, null only while an exact conversation owner is unresolved or no agent is chosen. */
    val requestedAgent: AgentId?,
    /** Requested chat mode, including the distinction between Resume and New. */
    val mode: CurrentFocusChatMode,
    /** Exact requested conversation, or null for Resume/New. */
    val exactConversation: ConversationId?,
    internal val owner: FocusRequestOwner,
)

/** An incarnation fence: requests from another or recreated workspace cannot match. */
internal class FocusRequestOwner

/** One publication includes navigation, request ordering, availability and source-local evidence. */
internal data class WorkspaceFocusState(
    val saved: CurrentFocusSnapshot,
    val generation: Long = 0,
    val revision: Long = 0,
    val agentAvailability: FocusAvailability = FocusAvailability.Unknown,
    val conversationAvailability: FocusAvailability = FocusAvailability.Unknown,
    val history: Map<AgentId, ConversationId> = emptyMap(),
    val owners: Map<ConversationId, AgentId> = emptyMap(),
    val settled: CurrentFocusSnapshot? = null,
)

internal fun CurrentFocus.valid(): Boolean {
    val decoded = ShellRoute.decode(pageRoute) ?: return false
    val canonicalPage = decoded !is ShellDestination.AgentEditor && decoded.agentId == null &&
        decoded.conversationId == null && ShellRoute.encode(decoded) == pageRoute
    val validMode = when (chatMode) {
        CurrentFocusChatMode.Exact -> conversationId != null
        CurrentFocusChatMode.Resume, CurrentFocusChatMode.New -> conversationId == null
    }
    val scopedDefault = conversationId?.value != "default" || agentId != null
    return canonicalPage && validMode && scopedDefault && agentId?.value?.isBlank() != true &&
        conversationId?.value?.isBlank() != true
}

internal fun CurrentFocusSnapshot.valid(): Boolean {
    return contractVersion == 1 && sourceKey.isNotBlank() && focus.valid() &&
        validFocusEditorRoute(editorRoute) && validFocusTabs()
}

internal fun validFocusEditorRoute(route: String?): Boolean {
    if (route == null) return true
    val editor = ShellRoute.decode(route) as? ShellDestination.AgentEditor ?: return false
    return editor.agentId?.isBlank() != true && ShellRoute.encode(editor) == route
}

private fun CurrentFocusSnapshot.validFocusTabs(): Boolean {
    val uniquePins = pinnedConversationIds.distinct().size == pinnedConversationIds.size
    val validIds = pinnedConversationIds.none { it.value.isBlank() } && previewConversationId?.value?.isBlank() != true
    val uniquePreview = previewConversationId !in pinnedConversationIds
    return uniquePins && validIds && uniquePreview
}

internal fun CurrentFocusSnapshot.detached(): CurrentFocusSnapshot = copy(pinnedConversationIds = pinnedConversationIds.toList())

internal fun CurrentFocusSnapshot.withTabs(tabs: ConversationTabsState): CurrentFocusSnapshot = copy(
    pinnedConversationIds = tabs.pinnedConversationIds.map(::ConversationId),
    previewConversationId = tabs.transientConversationId?.let(::ConversationId),
)

internal fun CurrentFocusSnapshot.tabs(): ConversationTabsState = ConversationTabsState(
    pinnedConversationIds.map { it.value }, previewConversationId?.value,
)
