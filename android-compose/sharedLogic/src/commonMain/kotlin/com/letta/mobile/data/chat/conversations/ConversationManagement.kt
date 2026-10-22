package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.branch.ConversationLineage
import com.letta.mobile.data.chat.branch.ConversationParent
import com.letta.mobile.data.chat.branch.PendingDraft
import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.search.ConversationMessageReader
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The open conversation: where a branch or `/recompile` starts. */
data class ConversationOrigin(val conversationId: ConversationId, val agentId: AgentId?)

/** A finished branch for the host to open: the new conversation, where it came from, and the prompt waiting for its composer. */
data class OpenedBranch(
    val conversation: Conversation,
    val source: BranchSource,
    /** An edit's prompt for the composer, or a rerun's to send at once; null for a fork. */
    val draft: PendingDraft? = null,
)

/**
 * The host's conversation list as [ConversationManagement] reads and writes it. The host owns the
 * list and its selection (desktop's chat controller, the web's [TimelineConversations]); the
 * management owns what a row action does on the backend.
 */
interface ConversationListHost {
    /** The backend's chat gateway now, probed for each capability; null when none is connected. */
    val gateway: ChatGateway?
    val isClosed: Boolean

    /** Emits (starting now) whenever the gateway may have changed, so what it can do is read again. */
    val gatewayChanges: Flow<Unit>

    fun origin(): ConversationOrigin?

    /** The title the list shows for the conversation, or null when it is not listed. */
    fun listedTitle(conversation: ConversationId): String?

    /** Shows [title] for the conversation; with [onlyIf], only while that is still its title. */
    fun showTitle(conversation: ConversationId, title: ConversationSummary, onlyIf: ConversationSummary? = null)

    fun showArchived(conversation: ConversationId, archived: Boolean)

    /** Marks the conversation as being deleted; false when it already is (a second press). */
    fun beginDelete(conversation: ConversationId): Boolean

    /** The delete ended: [behavior] is what it did, or null when it failed (the row stays). */
    suspend fun endDelete(conversation: ConversationId, behavior: ConversationDeleteBehavior?)

    /** Opens the branch; an edit's prompt goes in its composer and a rerun's is sent. */
    suspend fun openBranch(branch: OpenedBranch)

    fun showError(message: String)
}

/** The client-side list preferences: pins and fork lineage; a null member turns that feature off. */
data class ConversationManagementPrefs(
    val pins: PinnedConversations? = null,
    val lineage: ConversationLineage? = null,
) {
    companion object {
        /** Both, kept in [store] under their default keys. */
        fun from(store: SecureSettingsStore) = ConversationManagementPrefs(PinnedConversations(store), ConversationLineage(store))
    }
}

/**
 * A conversation list's row actions on the backend, for every host: rename (the conversation's
 * `summary`, shown at once and put back if the write fails while it still shows), pin, archive,
 * delete, `/recompile`, and fork / edit-and-resend / rerun ([branches]). The backend is the host's
 * [ChatGateway] (desktop's and the web's App Server or Iroh `admin_rpc` gateways), probed per
 * capability, so a backend without one changes nothing.
 */
class ConversationManagement(
    private val scope: CoroutineScope,
    private val host: ConversationListHost,
    private val prefs: ConversationManagementPrefs = ConversationManagementPrefs(),
) {
    val branches = ConversationBranches(scope, host, prefs)

    /** Whether the backend can fork, so the page offers "Fork from here", "Edit and resend" and `/fork`. */
    val supportsBranching: Boolean get() = branches.supported

    /**
     * `/recompile` for the open conversation, and where it stands. An Iroh host deployed before its
     * `conversation.recompile` relay does not advertise it, and the chat says the host needs an update.
     */
    val recompile = ConversationRecompileController(
        scope = scope,
        gateway = { host.gateway as? ConversationRecompileGateway },
        unavailableReason = { ConversationRecompileController.UNAVAILABLE_HOST_OUTDATED },
    )

    /** A conversation's newest messages, newest first, for the unified search's message index. */
    val messageReader = ConversationMessageReader { conversationId, limit ->
        val gateway = host.gateway.takeUnless { host.isClosed } ?: return@ConversationMessageReader emptyList()
        gateway.listConversationMessages(ConversationId(conversationId), limit = limit, after = null, order = NEWEST_FIRST)
    }

    /** The pinned conversation ids; lists show them first. */
    val pinnedConversationIds: StateFlow<Set<String>> =
        prefs.pins?.pinned ?: MutableStateFlow(emptySet<String>()).asStateFlow()

    /** Each fork's source conversation, for the list's "Forked from …". */
    val conversationParents: StateFlow<Map<String, ConversationParent>> =
        prefs.lineage?.parents ?: MutableStateFlow(emptyMap<String, ConversationParent>()).asStateFlow()

    /** What a delete really does on the backend now, for the confirm dialog's wording. */
    val deleteBehavior: ConversationDeleteBehavior get() = host.gateway?.deleteBehavior ?: ConversationDeleteBehavior.Permanent

    /** Recompiles the open conversation's system prompt; with nothing open, nothing happens. */
    fun recompileOpenConversation() {
        if (host.isClosed) return
        val origin = host.origin() ?: return
        recompile.recompile(ConversationRecompileRequest(origin.conversationId.value, origin.agentId?.value.orEmpty()))
    }

    /** Shows [message] as the chat's error, for shell actions that fail outside the chat. */
    fun reportError(message: String) {
        if (!host.isClosed) host.showError(message)
    }

    fun setPinned(conversation: ConversationId, pinned: Boolean) {
        if (!host.isClosed) prefs.pins?.setPinned(conversation.value, pinned)
    }

    /** Renames the conversation on the server (its `summary`); a blank title changes nothing. */
    fun rename(update: ConversationSummaryUpdate) {
        val title = update.summary.value.trim()
        if (!host.isClosed && title.isNotEmpty()) persistTitle(update.conversationId, title)
    }

    /**
     * Shows [title] at once and writes it through the gateway's [ConversationSummaryGateway]. If the
     * write fails the old title comes back, but only while [title] still shows, so a later rename
     * is never overwritten. A backend without titles changes nothing.
     */
    fun persistTitle(conversation: ConversationId, title: String) {
        val summaries = host.gateway as? ConversationSummaryGateway ?: return
        val original = host.listedTitle(conversation) ?: return
        host.showTitle(conversation, ConversationSummary(title))
        scope.launch {
            runCatchingCancellable { summaries.setConversationSummary(ConversationSummaryUpdate(conversation, ConversationSummary(title))) }
                .onFailure {
                    if (!host.isClosed) host.showTitle(conversation, ConversationSummary(original), onlyIf = ConversationSummary(title))
                }
        }
    }

    /**
     * Archives (non-destructive, recoverable) or restores the conversation: the list shows it at
     * once and the backend is told where it can be; the host keeps its own record of the choice.
     */
    fun setArchived(conversation: ConversationId, archived: Boolean) {
        if (host.isClosed) return
        host.showArchived(conversation, archived)
        val extras = host.gateway as? ChatGatewayExtras ?: return
        scope.launch { runCatchingCancellable { extras.setConversationArchived(conversation.value, archived) } }
    }

    /** Deletes the conversation on the backend; the host drops the row once the backend has. */
    fun delete(conversation: ConversationId) {
        if (host.isClosed) return
        val gateway = host.gateway ?: return
        if (!host.beginDelete(conversation)) return
        scope.launch {
            val deleted = runCatchingCancellable { gateway.deleteConversation(conversation.value) }
                .onFailure { failure -> if (!host.isClosed) host.showError(failure.message ?: DELETE_FAILED) }
                .isSuccess
            host.endDelete(conversation, gateway.deleteBehavior.takeIf { deleted })
        }
    }

    private companion object {
        /** `conversation_messages_list`'s order for the newest messages first. */
        const val NEWEST_FIRST = "desc"
        const val DELETE_FAILED = "Delete failed"
    }
}
