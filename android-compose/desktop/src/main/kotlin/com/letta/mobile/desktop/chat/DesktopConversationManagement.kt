package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
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

    /** letta-mobile-zxk2n.5: settled chats, markers, auto-pin and the chat's display options. */
    val marks = DesktopConversationMarks(prefs)

    /**
     * letta-mobile-zxk2n.5: `/recompile` for the open conversation, and where it stands. The local
     * App Server and REST backends recompile; over Iroh the host has no relay yet, which is the
     * only desktop backend without a route.
     */
    val recompile = ConversationRecompileController(
        scope = scope,
        gateway = { host.gateway as? ConversationRecompileGateway },
        unavailableReason = { ConversationRecompileController.UNAVAILABLE_OVER_IROH },
    )

    /** Recompiles the open conversation's system prompt; with nothing open, nothing happens. */
    fun recompileOpenConversation() {
        if (host.isClosed) return
        val origin = host.origin() ?: return
        recompile.recompile(ConversationRecompileRequest(origin.conversationId, origin.agentId.orEmpty()))
    }

    /** Shows [message] as the chat's error, for shell actions that fail outside the chat (a transcript save). */
    fun reportError(message: String) {
        if (!host.isClosed) host.showError(message)
    }

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
