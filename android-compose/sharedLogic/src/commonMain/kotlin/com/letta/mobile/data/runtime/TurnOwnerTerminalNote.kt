package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RuntimeRunStatus

/**
 * letta-mobile-kyqdt: TELEMETRY-ONLY. The last-seen terminal the engine records on the
 * active-turn owner and its lease. A null [source], [seq] or [scopeMatched] keeps the value
 * already recorded.
 *
 * @param status terminal lifecycle status carried by the draft.
 * @param source which collect-loop path observed this terminal (e.g. "terminal_lifecycle",
 *   "post_tool_usage", "completed_settle", "idle_timeout"). Descriptive only.
 * @param seq event_seq of the terminal-bearing frame if known, else null.
 * @param scopeMatched whether the terminal-bearing frame PASSED matches(scope). Null when not
 *   applicable (e.g. synthesized terminals).
 */
internal data class OwnerTerminalNote(
    val status: RuntimeRunStatus,
    val source: String? = null,
    val seq: Long? = null,
    val scopeMatched: Boolean? = null,
)

internal fun AppServerTurnEngine.ActiveTurnOwner.withTerminal(
    note: OwnerTerminalNote,
    atMs: Long,
): AppServerTurnEngine.ActiveTurnOwner = copy(
    lastTerminal = note.status.name,
    lastTerminalSource = note.source ?: lastTerminalSource,
    lastTerminalAtMs = atMs,
    lastTerminalSeq = note.seq ?: lastTerminalSeq,
    lastTerminalScopeMatched = note.scopeMatched ?: lastTerminalScopeMatched,
)

internal fun TurnLease.withTerminal(note: OwnerTerminalNote, atMs: Long): TurnLease = copy(
    lastTerminal = note.status.name,
    lastTerminalSource = note.source ?: lastTerminalSource,
    lastTerminalAtMs = atMs,
    lastTerminalSeq = note.seq ?: lastTerminalSeq,
    lastTerminalScopeMatched = note.scopeMatched ?: lastTerminalScopeMatched,
)
