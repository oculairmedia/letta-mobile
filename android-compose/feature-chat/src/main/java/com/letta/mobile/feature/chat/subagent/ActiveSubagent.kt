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
    /**
     * letta-mobile-vo9y1: the subagent's own agent id (`agent-local-*`) as
     * correlated by the shim registry's parent-dispatch -> subagent-run seam
     * ([SubagentEntry.subagentAgentId]). Present once the background dispatch
     * has returned identity; absent for synchronous dispatches that have not
     * yet reported it. When known it enables the "view conversation"
     * affordance ([canViewConversation]) which jumps to that agent's
     * transcript (conversation `default`).
     */
    val subagentAgentId: String? = null,
    /**
     * letta-mobile-29h9u: wall-clock epoch-ms at which this entry FIRST
     * became terminal (completed/failed), stamped by the source as the chip
     * transitions out of [Status.RUNNING]. Null while still running. Drives
     * the lingering window in [withLingeringTerminals]: a terminal chip stays
     * reviewable until `terminalAt + TERMINAL_LINGER_MS` before it is
     * dismissed. Not part of identity — it never changes the chip's slot.
     */
    val terminalAt: Long? = null,
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
        FAILED;

        /**
         * letta-mobile-29h9u: whether this status is terminal — a finished
         * outcome (success or failure), as opposed to still-[RUNNING]. Used
         * by the lingering transform to decide which chips earn a review
         * dwell, and by the bar to pick the success/failed visual style.
         */
        val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
    }

    val isActive: Boolean get() = status == Status.RUNNING

    /**
     * letta-mobile-29h9u: true once this entry has reached a terminal
     * lifecycle state (completed or failed). The active-only rule
     * ([ActiveSubagentSource.activeOnly]) filters these out instantly; the
     * lingering rule ([withLingeringTerminals]) keeps them briefly visible in
     * a success/failed style so the user can review the outcome.
     */
    val isTerminal: Boolean get() = status.isTerminal

    /**
     * letta-mobile-vo9y1: whether the "view conversation" affordance should
     * be offered for this chip. True only when the shim has correlated a
     * concrete subagent agent id; without it there is no transcript to open.
     * The synthetic self entry is excluded — its conversation is already the
     * one on screen.
     */
    val canViewConversation: Boolean
        get() = !isSelf && !subagentAgentId.isNullOrBlank()

    companion object {
        /**
         * letta-mobile-gnyf7: reserved stable id for the synthetic self
         * entry. A real subagent's id is a `tool_call_id` / `task_id`, never
         * this sentinel, so there is no collision risk.
         */
        const val SELF_ID = "__self__"

        /**
         * letta-mobile-29h9u: how long a terminal (completed/failed) chip
         * LINGERS in its success/failed style before being dismissed, so the
         * user can review the outcome instead of the chip vanishing instantly
         * under the active-only rule. Chosen at 8s — inside the bead's
         * requested 6-10s window.
         */
        const val TERMINAL_LINGER_MS: Long = 8_000L
    }
}
