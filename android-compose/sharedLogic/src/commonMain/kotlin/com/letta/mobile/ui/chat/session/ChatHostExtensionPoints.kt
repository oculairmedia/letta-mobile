package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.presence.ConversationRunState
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * letta-mobile-by4vm.98.3: the composer host inputs, fork/edit/rerun and live-status extension
 * points a session port reads through TimelineChatSessionOptions.
 */

/**
 * A composer `/` command the host offers and what running it does. Keyed by [label]: it is what
 * the palette shows and what is unique there.
 */
data class ChatHostCommand(
    val label: String,
    val description: String,
    /**
     * When true, selecting the command fills the composer (e.g. a server slash command the user
     * then edits and sends) rather than running an app action, so the palette does not take Enter.
     */
    val fillsComposer: Boolean = false,
    /** Removes the command (an installed skill); null where it cannot be removed. */
    val uninstall: (() -> Unit)? = null,
    val run: () -> Unit,
) {
    val command: ChatComposerCommand
        get() = ChatComposerCommand(label, label, description, fillsComposer = fillsComposer, removable = uninstall != null)
}

/** Composer chrome the host computes outside the conversation owner. */
data class ChatComposerHostInputs(
    val commands: List<ChatHostCommand> = emptyList(),
    val mentionables: List<Mentionable> = emptyList(),
    val contextUsage: ContextWindowUsageState? = null,
    /** Overrides the composer's default placeholder; null keeps the shared one. */
    val placeholder: String? = null,
)

/** The page's timeline at the moment the user asked for a branch. */
data class ChatBranchView(val messages: List<UiMessage>, val hasOlderMessages: Boolean)

/** What a host's branching can do now: fork (fork, edit and resend, rerun) and name a fork. */
data class ChatBranchSupport(val fork: Boolean = false, val nameForks: Boolean = false)

/**
 * A chat host's fork / edit-and-resend / rerun extension point (letta-mobile-bzvro.15/.16, 7mys7).
 * Each action branches the conversation from a message of [ChatBranchView] and opens the branch.
 */
interface ChatBranchingSource {
    val support: StateFlow<ChatBranchSupport>

    fun forkFrom(message: UiMessage, view: ChatBranchView, name: String? = null)

    fun editAndResend(message: UiMessage, view: ChatBranchView)

    fun rerun(message: UiMessage, view: ChatBranchView)
}

/**
 * letta-mobile-bzvro.7: the live status of the conversation [key] names, from a window's run
 * registry; idle when nothing is selected or the conversation has no run.
 */
fun runLiveStatus(
    runs: Flow<Map<String, ConversationRunState>>,
    key: Flow<String?>,
): Flow<RuntimeLiveStatus> =
    combine(runs, key.distinctUntilChanged()) { byKey, current -> current?.let(byKey::get)?.live ?: RuntimeLiveStatus.Idle }
        .distinctUntilChanged()
