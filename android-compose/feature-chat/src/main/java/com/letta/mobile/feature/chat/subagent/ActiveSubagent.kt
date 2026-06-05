package com.letta.mobile.feature.chat.subagent

import androidx.compose.runtime.Immutable

/**
 * letta-mobile-73o2h.2: clean UI-facing data model for a single
 * currently-active subagent. This is the contract the bottom status bar
 * renders against.
 *
 * The model is intentionally decoupled from any wire/WS shape. The real
 * active-subagent registry over the mobile WebSocket (letta-mobile-73o2h.1)
 * is not yet merged; when it lands, its repository simply maps its frames
 * into [ActiveSubagent] and feeds them through [ActiveSubagentSource]. The
 * bar never scans the parent frame stream — see the epic context.
 *
 * `@Immutable` lets Compose treat instances as stable, so an unchanged
 * subagent does not force recomposition of its chip. Combined with stable
 * keys in [ActiveSubagentBar] this keeps the bar cheap and avoids
 * regressing the streaming-jank work (letta-mobile-rmzmo).
 */
@Immutable
data class ActiveSubagent(
    /**
     * Stable identity for this dispatch. Maps to the parent Agent
     * tool_call's `tool_call_id` (sync) or the `task_id` (background) from
     * the .1 registry. MUST be stable across status updates so the chip
     * keeps its slot / does not remount.
     */
    val id: String,
    /** Human description from the dispatch args (`description`). */
    val description: String,
    /** The dispatch `subagent_type` (e.g. "general", "researcher"). */
    val subagentType: String,
    /** Current lifecycle status. The bar only renders [Status.RUNNING]. */
    val status: Status,
    /**
     * letta-mobile-gnyf7: marks the synthetic "self"/"you" entry — the
     * MAIN/foreground agent's OWN TodoWrite plan for the active conversation,
     * as opposed to a DISPATCHED subagent. Rendered with a distinct
     * icon/label and pinned at the head of the bar while a plan is active.
     * Its [id] is the reserved [SELF_ID] sentinel; the tap-to-todolist sheet
     * resolves its checklist from the self-todo source, not the subagent
     * registry.
     */
    val isSelf: Boolean = false,
) {
    enum class Status {
        /** Dispatched and still working — the only state shown in the bar. */
        RUNNING,

        /** Terminal: finished successfully. Filtered out of the active bar. */
        COMPLETED,

        /**
         * Terminal: failed, including the 600s stream-timeout failure mode
         * surfaced by the .1 registry. Filtered out of the active bar.
         */
        FAILED,
    }

    val isActive: Boolean get() = status == Status.RUNNING

    companion object {
        /**
         * letta-mobile-gnyf7: reserved stable id for the synthetic self
         * entry. A real subagent's id is a `tool_call_id` / `task_id`, never
         * this sentinel, so there is no collision risk.
         */
        const val SELF_ID = "__self__"
    }
}
