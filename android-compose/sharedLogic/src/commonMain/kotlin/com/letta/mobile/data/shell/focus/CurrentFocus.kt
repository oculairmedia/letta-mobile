package com.letta.mobile.data.shell.focus

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val CURRENT_FOCUS_VERSION = 1

/** How a chat target is interpreted, independently of whether its data has loaded. */
@Serializable
enum class CurrentFocusChatMode {
    /** Resolve only this agent's human chat; no conversation has been selected yet. */
    @SerialName("resume") Resume,

    /** Open exactly [CurrentFocus.conversationId]; absence or deletion never permits fallback. */
    @SerialName("exact") Exact,

    /** An explicitly empty chat; restoring it must not replay a create or send operation. */
    @SerialName("new") New,
}

/**
 * Immutable v1 workspace focus value. [pageRoute] is a canonical ShellRoute without agent or
 * conversation query parameters and never an agent-editor route. Identity is stored only here;
 * routes, names, roster membership and loading/availability are projections. The shell workspace validates and owns live transitions; see current-focus-v1.md.
 */
@Serializable
data class CurrentFocus(
    val pageRoute: String = "home",
    val agentId: AgentId? = null,
    val conversationId: ConversationId? = null,
    val chatMode: CurrentFocusChatMode = CurrentFocusChatMode.Resume,
)

/**
 * One atomic saved workspace envelope, bound to a trusted opaque [sourceKey], never a backend URL
 * or credential. Tabs contain references, not a second selected tab. [editorRoute] is null, an
 * existing agent-editor route, or the new-agent route; it never replaces [focus]. Editor drafts,
 * transport state, pending operations and secrets are deliberately outside this value.
 */
@Serializable
data class CurrentFocusSnapshot(
    val sourceKey: String,
    val focus: CurrentFocus = CurrentFocus(),
    val pinnedConversationIds: List<ConversationId> = emptyList(),
    val previewConversationId: ConversationId? = null,
    val editorRoute: String? = null,
    val railExpanded: Boolean = false,
    val contractVersion: Int = CURRENT_FOCUS_VERSION,
)

/**
 * Inputs to v1 startup arbitration. New [deepLink] and [explicitRoute] entries have already been
 * validated and bound to [sourceKey]; their envelopes supply focus and an optional editor target,
 * never tab order or rail width. Replayed saved route arguments are not new entries. A legacy
 * settings record may become [settingsSeed] only after its source is established independently.
 * A null candidate is absent, while an explicit Home or empty chat is a complete candidate.
 */
@Serializable
data class CurrentFocusInputs(
    val sourceKey: String,
    val deepLink: CurrentFocusSnapshot? = null,
    val explicitRoute: CurrentFocusSnapshot? = null,
    val restored: CurrentFocusSnapshot? = null,
    val settingsSeed: CurrentFocusSnapshot? = null,
)

/**
 * Pure startup precedence, without storage or target resolution. A new external
 * entry wins over a new route, then same-source saved state, then a verified settings seed, then
 * Home. New entries retain same-source saved tabs/rail and supply their own editor target (null
 * closes it). Settings seeds contribute focus only. Other versions/sources cannot restore state.
 */
fun initialCurrentFocus(inputs: CurrentFocusInputs): CurrentFocusSnapshot {
    val versioned = inputs.restored?.takeIf { it.contractVersion == CURRENT_FOCUS_VERSION }
    val restored = versioned?.takeIf { it.sourceKey == inputs.sourceKey }
    val base = restored ?: CurrentFocusSnapshot(inputs.sourceKey, railExpanded = versioned?.railExpanded == true)
    val entry = listOfNotNull(inputs.deepLink, inputs.explicitRoute)
        .firstOrNull { it.sourceKey == inputs.sourceKey && it.contractVersion == CURRENT_FOCUS_VERSION }
    return when {
        entry != null -> base.copy(focus = entry.focus, editorRoute = entry.editorRoute)
        restored != null -> restored
        else -> inputs.settingsSeed
            ?.takeIf { it.sourceKey == inputs.sourceKey && it.contractVersion == CURRENT_FOCUS_VERSION }
            ?.let { base.copy(focus = it.focus) }
            ?: base
    }
}
