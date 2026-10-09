package com.letta.mobile.ui.chat.session

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.runtime.ApprovalBinding
import kotlin.jvm.JvmInline

/*
 * letta-mobile-bglj6.1.10.1: the ids and values the shared chat page hands its owner through
 * [ChatActions], typed so an owner cannot pass a run id where a message id belongs.
 */

/** A run on the timeline (the key its collapse state is kept under). */
@JvmInline
value class ChatRunId(val value: String)

/** A timeline message (reasoning to expand, a truncated tool result to load in full). */
@JvmInline
value class ChatMessageId(val value: String)

/** An A2UI surface the page can dismiss. */
@JvmInline
value class A2uiSurfaceId(val value: String)

/** An A2UI action snackbar the page has shown. */
@JvmInline
value class A2uiSnackbarId(val value: Long)

/** A model the composer's picker selected, as the owner's catalog names it. */
@JvmInline
value class ChatModelHandle(val value: String)

/** A conversation's working directory, as the host's folder picker returned it. */
@JvmInline
value class ChatWorkingDirectory(val path: String)

/** The user's answer to an approval request: allow or deny these tool calls, with an optional reason. */
@Immutable
data class ChatApprovalAnswer(
    val requestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
    /** letta-mobile-bzvro.11: the `permission_suggestions` ids chosen with an approval ("always allow"). */
    val selectedSuggestionIds: List<String> = emptyList(),
    /**
     * The parked control request the [selectedSuggestionIds] were offered by. An "always allow" is
     * bound to exactly the details the card drew, so it can never persist a rule for a different request.
     */
    val suggestionBinding: ApprovalBinding? = null,
)
