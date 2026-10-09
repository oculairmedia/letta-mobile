package com.letta.mobile.ui.chat.session

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.runtime.PermissionModeState
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * letta-mobile-bglj6.1: everything the shared chat page's composer draws.
 *
 * [com.letta.mobile.ui.chat.render.ChatUiState] is the timeline contract; this is the
 * composer's. They are separate flows because the composer changes on every keystroke
 * and the timeline must not recompose with it.
 *
 * The draft lives with the session owner, not in the composable, so the docked bar and
 * the full-screen page edit the SAME draft: switching mode never loses text or images.
 */
@Immutable
data class ChatComposerUiState(
    val text: String = "",
    val attachments: ImmutableList<MessageContentPart.Image> = persistentListOf(),
    /** A user-facing composer error (attachment too large, send blocked), or null. */
    val error: String? = null,
    /** The owner will accept a send right now (connection, run and payload permitting). */
    val canSend: Boolean = false,
    /**
     * The owner takes input at all right now (connected, not mid-submit). False greys the prompt
     * field and keeps it from taking focus, as the legacy composer's `canSendMessages` did; it is
     * independent of the payload, unlike [canSend] for some owners.
     */
    val acceptsInput: Boolean = true,
    /** A send during an active run is queued rather than refused. */
    val canQueueWhileStreaming: Boolean = false,
    /** Overrides the default placeholder; null uses the shared resource string. */
    val placeholder: String? = null,
    val maxAttachments: Int = AttachmentLimits.Default.maxAttachmentCount,
    /** The owner's image limits: picked images are scaled and encoded to these. */
    val attachmentLimits: AttachmentLimits = AttachmentLimits.Default,
    val commands: ImmutableList<ChatComposerCommand> = persistentListOf(),
    val mentionables: ImmutableList<Mentionable> = persistentListOf(),
    val model: ChatModelUiState? = null,
    val contextUsage: ContextWindowUsageState? = null,
    val workingDirectory: ChatWorkingDirectoryUiState? = null,
    val permissionMode: ChatPermissionModeUiState? = null,
    val backgroundProcesses: ImmutableList<com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess> = persistentListOf(),
) {
    val hasPayload: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()
}

/**
 * A `/` command offered by the composer's autocomplete.
 *
 * [fillsComposer] commands (server slash commands, skills) put their text in the draft for
 * the user to finish and send; the others run an app action through
 * [ChatActions.runComposerCommand] and never reach the agent.
 */
@Immutable
data class ChatComposerCommand(
    val id: String,
    val label: String,
    val description: String = "",
    val fillsComposer: Boolean = false,
    /** Installed server commands the user may uninstall. */
    val removable: Boolean = false,
) {
    companion object {
        fun fromSlashCommand(command: SlashCommand): ChatComposerCommand =
            ChatComposerCommand(
                id = command.command,
                label = command.command,
                description = command.description,
                fillsComposer = true,
                removable = command.installed,
            )
    }
}

/** The conversation's model, and what the picker may switch it to. */
@Immutable
data class ChatModelUiState(
    val currentHandle: String?,
    val currentLabel: String,
    val currentEffort: String? = null,
    val options: ImmutableList<ChatModelOption> = persistentListOf(),
    val isSwitching: Boolean = false,
)

@Immutable
data class ChatModelOption(
    val handle: String,
    val label: String,
    val provider: String? = null,
    /** Named reasoning efforts this model accepts; empty when it has none. */
    val reasoningEfforts: ImmutableList<String> = persistentListOf(),
)

/** The coding agent's working directory, for owners whose backend has one. */
@Immutable
data class ChatWorkingDirectoryUiState(
    val path: String?,
    val isLoading: Boolean = false,
    val branch: String? = null,
)

/**
 * letta-mobile-bzvro.13: the permission-mode chip. [selected] is the mode the server confirmed;
 * [pending] a request still waiting for the server's `update_device_status` echo; [unconfirmed] a
 * request whose outcome is unknown (the server may run [selected] or it), shown as such rather than
 * as the old mode; [appliesOnStart] a choice for a conversation whose runtime has not started.
 * The chip is shown disabled, with [unavailableReason], where this owner cannot change the mode.
 */
@Immutable
data class ChatPermissionModeUiState(
    val selected: AppServerPermissionMode,
    val pending: AppServerPermissionMode? = null,
    val unconfirmed: AppServerPermissionMode? = null,
    val appliesOnStart: Boolean = false,
    val unavailableReason: String? = null,
    val options: ImmutableList<AppServerPermissionMode> = AppServerPermissionMode.entries.toImmutableList(),
) {
    val canChange: Boolean get() = unavailableReason == null && pending == null
}

/** The chip's state for a runtime's [PermissionModeState]; [unavailableReason] locks it. */
fun PermissionModeState.toUiState(unavailableReason: String? = null): ChatPermissionModeUiState =
    ChatPermissionModeUiState(
        selected = mode,
        pending = pending,
        unconfirmed = unconfirmed,
        appliesOnStart = appliesOnStart,
        unavailableReason = unavailableReason,
    )
