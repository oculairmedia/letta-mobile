package com.letta.mobile.ui.chat.subagents

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentKind
import com.letta.mobile.data.model.SubagentStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Clean UI-facing data model for a single
 * currently-active subagent. This is the contract the bottom status bar
 * renders against.
 *
 * The model is intentionally decoupled from any wire/WS shape. The real
 * active-subagent registry over the mobile WebSocket
 * is not yet merged; when it lands, its repository simply maps its frames
 * into [ActiveSubagent] and feeds them through [ActiveSubagentSource]. The
 * bar never scans the parent frame stream — see the epic context.
 *
 * `@Immutable` lets Compose treat instances as stable, so an unchanged
 * subagent does not force recomposition of its chip. Combined with stable
 * keys in [ActiveSubagentBar] this keeps the bar cheap and avoids
 * regressing the streaming-jank work.
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
     * Marks the synthetic "self"/"you" entry — the
     * MAIN/foreground agent's OWN TodoWrite plan for the active conversation,
     * as opposed to a DISPATCHED subagent. Rendered with a distinct
     * icon/label and pinned at the head of the bar while a plan is active.
     * Its [id] is the reserved [SELF_ID] sentinel; the tap-to-todolist sheet
     * resolves its checklist from the self-todo source, not the subagent
     * registry.
     */
    val isSelf: Boolean = false,
    /**
     * Which KIND of work this entry represents. The
     * active bar is a UNIFIED registry: dispatched subagents, the synthetic
     * self plan, AND long-running background tool calls (`run_in_background`
     * Bash / Task) all flow through the SAME [ActiveSubagent] model and the
     * same lifecycle (running -> terminal linger -> dismiss). The kind only
     * differentiates the glyph + label per chip; it is NOT a parallel chip
     * system. Defaults to [Kind.SUBAGENT] so existing call sites are
     * unaffected; [isSelf] still implies [Kind.SELF] for back-compat.
     */
    val kind: Kind = if (isSelf) Kind.SELF else Kind.SUBAGENT,
    /**
     * The entry's TodoWrite progress (completed / total)
     * used to drive the DETERMINATE progress ring around the chip icon. Null
     * while no checklist has arrived yet — the ring then renders empty/indeterminate
     * until the first TodoWrite lands. For background tool tasks that have no
     * todo checklist, this stays null and the ring reads as indeterminate
     * activity.
     */
    val progress: SubagentTodoProgress? = null,
    /**
     * Wall-clock epoch-ms of the LAST observed todo-state
     * change (or, absent todos, the last activity) for this entry while it is
     * running. Drives the NEW "stuck" heuristic: if a running entry has not
     * changed state for [STUCK_THRESHOLD_MS], the ring turns YELLOW. Null when
     * unknown (treated as not-stuck so we never false-positive on a chip we
     * have no timing for).
     */
    val lastUpdateAt: Long? = null,
    /**
     * The subagent's own agent id (`agent-local-*`) as
     * correlated by the shim registry's parent-dispatch -> subagent-run seam
     * ([SubagentEntry.subagentAgentId]). Present once the background dispatch
     * has returned identity; absent for synchronous dispatches that have not
     * yet reported it. When known it enables the "view conversation"
     * affordance ([canViewConversation]) which jumps to that agent's
     * transcript (conversation `default`).
     */
    val subagentAgentId: String? = null,
    /**
     * The subagent's own conversation id when the
     * shim supplies one. Mobile must not infer this from the parent
     * conversation or from a default conversation id; when absent, callers
     * must resolve it from the registry/detail source or refuse navigation.
     */
    val subagentConversationId: String? = null,
    /**
     * Wall-clock epoch-ms at which this entry FIRST
     * became terminal (completed/failed), stamped by the source as the chip
     * transitions out of [Status.RUNNING]. Null while still running. Drives
     * the lingering window in [withLingeringTerminals]: a terminal chip stays
     * reviewable until `terminalAt + TERMINAL_LINGER_MS` before it is
     * dismissed. Not part of identity — it never changes the chip's slot.
     */
    val terminalAt: Long? = null,
) {
    /**
     * The unified chip taxonomy. One registry, three
     * kinds, differentiated only by glyph + label. NOT a parallel system.
     */
    enum class Kind {
        /** A dispatched subagent (Task/Agent tool_call). */
        SUBAGENT,

        /** The MAIN/foreground agent's OWN TodoWrite plan (the "self" entry). */
        SELF,

        /**
         * A long-running BACKGROUND tool call (`run_in_background` Bash / Task)
         * that OUTLIVES the turn — e.g. "Building APK…", "Waiting on CI…". It
         * is a tool task, not an agent, so it gets a distinct glyph/label.
         */
        BACKGROUND_TASK,
    }

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
         * Whether this status is terminal — a finished
         * outcome (success or failure), as opposed to still-[RUNNING]. Used
         * by the lingering transform to decide which chips earn a review
         * dwell, and by the bar to pick the success/failed visual style.
         */
        val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
    }

    val isActive: Boolean get() = status == Status.RUNNING

    /**
     * True once this entry has reached a terminal
     * lifecycle state (completed or failed). The active-only rule
     * ([ActiveSubagentSource.activeOnly]) filters these out instantly; the
     * lingering rule ([withLingeringTerminals]) keeps them briefly visible in
     * a success/failed style so the user can review the outcome.
     */
    val isTerminal: Boolean get() = status.isTerminal

    /**
     * Whether the "view conversation" affordance should
     * be offered for this chip. True only when the shim has correlated a
     * concrete subagent agent id; without it there is no transcript to open.
     * The synthetic self entry is excluded — its conversation is already the
     * one on screen.
     */
    val canViewConversation: Boolean
        get() = !isSelf && !subagentAgentId.isNullOrBlank()

    val subagentNavigationConversationId: String?
        get() = subagentConversationId?.takeIf { it.isNotBlank() }

    /**
     * The ring FILL fraction in [0f, 1f]. Driven by the
     * TodoWrite completion fraction; terminal-success settles to a fully
     * filled ring (1f) and terminal-failure to whatever fraction it reached
     * (the red ring is the signal, not the fill). Null [progress] (no todos
     * yet, or a background task with no checklist) reads as 0f — the bar then
     * renders the ring as indeterminate activity rather than a 0% determinate
     * arc.
     */
    val ringFraction: Float
        get() = when {
            status == Status.COMPLETED -> 1f
            else -> progress?.fraction ?: 0f
        }

    /**
     * Whether the ring has determinate progress to show.
     * False when there is no todo checklist yet (or the task carries none) —
     * the bar then renders an empty/indeterminate ring (respecting reduced
     * motion) until the first TodoWrite arrives. Always determinate once
     * terminal (the outcome is known).
     */
    val hasDeterminateProgress: Boolean
        get() = isTerminal || (progress != null && progress.total > 0)

    /**
     * Derive the ring COLOR state from lifecycle + the
     * stuck heuristic. Pure (clock injected) so the state->color mapping is
     * unit-testable.
     *  - [Status.FAILED] -> [RingState.ERROR] (red).
     *  - [Status.COMPLETED] -> [RingState.SUCCESS] (filled green / check).
     *  - running + no todo-state change for [STUCK_THRESHOLD_MS] -> [RingState.STUCK] (yellow).
     *  - running + progressing/healthy -> [RingState.RUNNING] (green).
     */
    fun ringState(now: Long): RingState = when (status) {
        Status.FAILED -> RingState.ERROR
        Status.COMPLETED -> RingState.SUCCESS
        Status.RUNNING -> {
            val last = lastUpdateAt
            if (last != null && now - last >= STUCK_THRESHOLD_MS) {
                RingState.STUCK
            } else {
                RingState.RUNNING
            }
        }
    }

    /**
     * The unambiguous status LABEL for this entry. A
     * running/dispatched entry must NEVER read as "completed". Terminal
     * success reads "finished"/"complete" only when genuinely terminal.
     * Background tasks get task-flavoured wording (a tool task is not an
     * agent). The [description] is appended by the caller; this is the bare
     * state phrase.
     */
    val statusLabel: String
        get() = when (kind) {
            Kind.SELF -> when (status) {
                Status.RUNNING -> "Your plan"
                Status.COMPLETED -> "Plan complete"
                Status.FAILED -> "Plan failed"
            }
            Kind.BACKGROUND_TASK -> when (status) {
                Status.RUNNING -> "Background task running"
                Status.COMPLETED -> "Background task finished"
                Status.FAILED -> "Background task failed"
            }
            Kind.SUBAGENT -> when (status) {
                // Never say "completed" for a running/dispatched subagent.
                Status.RUNNING -> "Subagent running"
                Status.COMPLETED -> "Subagent finished"
                Status.FAILED -> "Subagent failed"
            }
        }

    companion object {
        /**
         * Reserved stable id for the synthetic self
         * entry. A real subagent's id is a `tool_call_id` / `task_id`, never
         * this sentinel, so there is no collision risk.
         */
        const val SELF_ID = "__self__"

        /**
         * How long a terminal (completed/failed) chip
         * LINGERS in its success/failed style before being dismissed, so the
         * user can review the outcome instead of the chip vanishing instantly
         * under the active-only rule. Chosen at 8s — inside the bead's
         * requested 6-10s window.
         */
        const val TERMINAL_LINGER_MS: Long = 8_000L

        /**
         * How long a RUNNING entry may go without host-observed progress
         * ([lastUpdateAt]) before its ring flips to the YELLOW "stuck" state.
         *  (RC4): progress is now a new tool call, and a
         * model routinely thinks for well over a minute between tool calls,
         * so the old 35s (todo-change semantics) flagged healthy work.
         * Five minutes is the stuck-suspected soft threshold.
         */
        const val STUCK_THRESHOLD_MS: Long = 5 * 60_000L

        /**
         * Letta.js sleeptime `Agent(subagent_type:"reflection")`
         * dispatches are background work the user did not ask to see. Shared by
         * [ActiveSubagentBar] and [ActiveSubagentRings] so production rings and
         * the chip bar stay in lockstep.
         */
        const val REFLECTION_SUBAGENT_TYPE: String = "reflection"
    }
}

/**
 * True when this entry is a sleeptime reflection dispatch that must stay out
 * of the active-subagent chrome (bar chips and production rings).
 * delegates to [SubagentKind] so the host's
 * "Reflection" / "Reflection integration" labels are hidden too.
 */
fun ActiveSubagent.isHiddenReflection(): Boolean =
    SubagentKind.fromWire(subagentType).isBackground

/**
 * Whether this entry keeps the rings' clock ticking: a visible run that is still going or still
 * lingering after it ended. A hidden reflection never does.
 */
fun ActiveSubagent.keepsRingsTicking(): Boolean = !isHiddenReflection() && (isTerminal || isActive)

/**
 * Whether the active-subagent bar should reserve layout: self plan and/or any
 * non-reflection dispatched entry. An all-reflection snapshot must hide the
 * bar entirely (no empty LazyRow padding).
 */
fun Iterable<ActiveSubagent>.hasVisibleActiveSubagentBarEntries(): Boolean =
    any { it.isSelf || !it.isHiddenReflection() }

/**
 * The LINGERING-terminal visibility rule.
 *
 * Pure, testable transform over a snapshot of subagents (which may carry both
 * running and recently-terminal entries, each terminal one stamped with
 * [ActiveSubagent.terminalAt]). It relaxes the strict active-only rule so a
 * just-completed/failed chip stays reviewable for a short dwell instead of
 * vanishing instantly:
 *
 *  - Still-running chips ([ActiveSubagent.isActive]) always pass — the
 *    unchanged active-only behaviour.
 *  - Terminal chips pass only while inside their linger window, i.e. until
 *    `terminalAt + `[ActiveSubagent.TERMINAL_LINGER_MS]` <= now`. After that
 *    they are dropped (dismissed).
 *  - A terminal chip with no [ActiveSubagent.terminalAt] stamp is treated as
 *    expired and dropped — a defensive fallback so an unstamped terminal can
 *    never linger forever.
 *
 * Input order is kept and only filtering happens, so the rings reduce by
 * simple replacement. [now] is injected (wall-clock epoch-ms) so the rule is
 * deterministic under test.
 */
fun ImmutableList<ActiveSubagent>.withLingeringTerminals(now: Long): ImmutableList<ActiveSubagent> =
    filter { entry ->
        when {
            entry.isActive -> true
            entry.isTerminal -> {
                val stampedAt = entry.terminalAt ?: return@filter false
                now - stampedAt < ActiveSubagent.TERMINAL_LINGER_MS
            }
            else -> true
        }
    }.toImmutableList()

/**
 *  (shared): one registry entry as the UI's
 * [ActiveSubagent], the same on every client. The id is the `toolCallId`, else the `taskId`
 * (a background dispatch), else a synthetic key so the ring keeps a stable slot.
 *  - an entry with a `taskId` but no `toolCallId` is a background tool task.
 *  - The subagent's agent and conversation ids are carried when present;
 *    the conversation is never guessed.
 *  - the wire `todo_progress` drives the ring's determinate fill.
 *  -  (RC4): the stuck baseline is the host's last observed progress, never the start.
 */
fun SubagentEntry.toActiveSubagent(): ActiveSubagent = ActiveSubagent(
    id = toolCallId.takeIf { it.isNotBlank() }
        ?: taskId?.takeIf { it.isNotBlank() }
        ?: "subagent-${hashCode()}",
    description = description,
    subagentType = subagentType,
    status = status.toActiveSubagentStatus(),
    kind = if (toolCallId.isBlank() && !taskId.isNullOrBlank()) ActiveSubagent.Kind.BACKGROUND_TASK else ActiveSubagent.Kind.SUBAGENT,
    subagentAgentId = subagentAgentId?.takeIf { it.isNotBlank() },
    subagentConversationId = subagentConversationId?.takeIf { it.isNotBlank() },
    progress = todoProgress?.let { SubagentTodoProgress(completed = it.completed, total = it.total) },
    lastUpdateAt = lastActivityAtMs,
    terminalAt = terminalAtEpochMs,
)

/**
 * Canonicalize through the SHARED vocabulary before mapping, so the App Server's
 * terminal `success` reads as finished. `cancelled` is terminal and maps to
 * FAILED. An unrecognized status keeps the ring visible (treated as still running).
 */
fun String.toActiveSubagentStatus(): ActiveSubagent.Status = when (SubagentStatus.normalize(this) ?: this) {
    SubagentStatus.RUNNING -> ActiveSubagent.Status.RUNNING
    SubagentStatus.COMPLETED -> ActiveSubagent.Status.COMPLETED
    SubagentStatus.FAILED, SubagentStatus.CANCELLED -> ActiveSubagent.Status.FAILED
    else -> ActiveSubagent.Status.RUNNING
}

/**
 * The COLOR/semantic state of a chip's progress ring,
 * decoupled from Compose so the state->color mapping is unit-testable. The
 * bar maps each to a design-system tint:
 *  - [RUNNING]  -> green (running and progressing/healthy)
 *  - [STUCK]    -> yellow (running but no todo-state change for ~30-45s)
 *  - [ERROR]    -> red (terminal failure)
 *  - [SUCCESS]  -> filled green / check (terminal success)
 */
enum class RingState { RUNNING, STUCK, ERROR, SUCCESS }
