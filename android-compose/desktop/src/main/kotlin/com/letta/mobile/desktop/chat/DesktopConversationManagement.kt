package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The controller state [DesktopConversationManagement] reads and writes, beyond what a branch needs. */
internal interface DesktopConversationListHost : DesktopBranchHost {
    val isClosed: Boolean

    /** The title the list shows for the conversation, or null when it is not listed. */
    fun listedTitle(conversationId: ConversationId): String?

    /** Shows [title] for the conversation; with [onlyIf], only while that is still its title. */
    fun showTitle(conversationId: ConversationId, title: ConversationSummary, onlyIf: ConversationSummary? = null)
}

/**
 * letta-mobile-bzvro.15-.18: the desktop chat's conversation management: titles (rename and the
 * generated first-message title), pins, the recent-models list, and fork / edit-and-resend. The
 * logic is shared (PinnedConversations, RecentModelsStore, ConversationBranching); this binds it to
 * the controller through [DesktopConversationListHost].
 */
internal class DesktopConversationManagement(
    private val scope: CoroutineScope,
    private val host: DesktopConversationListHost,
    private val prefs: DesktopConversationPrefs,
) {
    private val brancher = DesktopConversationBrancher(scope, host)

    /** The pinned conversation ids; the sidebar lists them first. */
    val pinnedConversationIds: StateFlow<Set<String>> =
        prefs.pins?.pinned ?: MutableStateFlow(emptySet<String>()).asStateFlow()

    /** Models switched to most recently (newest first), for the picker's "Recent" group. */
    val recentModels: StateFlow<List<String>> =
        prefs.recentModels?.recent ?: MutableStateFlow(emptyList<String>()).asStateFlow()

    /** Whether the active backend can fork, so the page offers "Fork from here" and "Edit and resend". */
    val supportsBranching: Boolean get() = brancher.supported

    fun setPinned(conversationId: ConversationId, pinned: Boolean) {
        if (!host.isClosed) prefs.pins?.setPinned(conversationId.value, pinned)
    }

    /** Renames the conversation on the server (its `summary`); a blank title changes nothing. */
    fun rename(update: ConversationSummaryUpdate) {
        val title = update.summary.value.trim()
        if (!host.isClosed && title.isNotEmpty()) persistTitle(update.conversationId, title)
    }

    /**
     * Shows [title] at once and writes it through the gateway's [ConversationSummaryGateway];
     * the old title comes back if the write fails. A backend without titles changes nothing.
     */
    fun persistTitle(conversationId: ConversationId, title: String) {
        val summaries = host.gateway as? ConversationSummaryGateway ?: return
        val original = host.listedTitle(conversationId) ?: return
        host.showTitle(conversationId, ConversationSummary(title))
        scope.launch {
            try {
                summaries.setConversationSummary(
                    ConversationSummaryUpdate(conversationId, ConversationSummary(title)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!host.isClosed) {
                    host.showTitle(conversationId, ConversationSummary(original), onlyIf = ConversationSummary(title))
                }
            }
        }
    }

    /** A model the conversation switched to becomes recent (the store may write the TUI's settings file). */
    suspend fun recordModel(model: String) {
        val recents = prefs.recentModels ?: return
        withContext(Dispatchers.IO) { recents.record(model) }
    }

    fun forkFrom(message: UiMessage, view: DesktopBranchView) {
        if (!host.isClosed) brancher.forkFrom(message, view)
    }

    fun editAndResend(message: UiMessage, view: DesktopBranchView) {
        if (!host.isClosed) brancher.editAndResend(message, view)
    }
}
