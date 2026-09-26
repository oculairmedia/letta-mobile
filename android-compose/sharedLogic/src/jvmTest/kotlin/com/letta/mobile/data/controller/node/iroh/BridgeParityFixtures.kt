package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerPermissionMode

/**
 * letta-mobile-qygvv.14: the recorded turns the bridge parity gate replays. Every fixture under
 * `appserver/bridge-parity/` that ends in `turn_finished` belongs here (the lint test enforces it).
 * Shapes follow protocol_v2.d.ts (letta-code 0.32.17) and the live capture in
 * `appserver/golden/letta-code-0.32.3-live.jsonl`; content is redacted.
 */
internal object BridgeParityFixtures {
    private const val DIR = "appserver/bridge-parity"

    val THINKING = ParityFixture("$DIR/thinking-turn.jsonl", "cm-parity-thinking-1")

    /** letta-mobile-1n5py: an input queued behind another viewer's turn, then dequeued and run. */
    val QUEUED = ParityFixture("$DIR/queued-turn.jsonl", "cm-parity-queued-1")

    /** An always-ask tool: the server sends control_request can_use_tool, the Unrestricted engine allows it. */
    val TOOL_CALL_AUTO_ALLOWED = ParityFixture(
        "$DIR/tool-call-auto-allowed.jsonl",
        "cm-parity-tool-allow-1",
    )

    /** Strict mode: the user denies the control_request; the tool returns an error and the agent replies. */
    val APPROVAL_DENIED = ParityFixture(
        "$DIR/approval-denied.jsonl",
        "cm-parity-denied-1",
        EngineSetup(permissionMode = AppServerPermissionMode.Strict, driver = DenyApprovalsDriver),
    )

    /** A host (canvas.*) tool: external_tool_call_request, the client's response, then the reply. */
    val EXTERNAL_TOOL = ParityFixture(
        "$DIR/external-tool-round-trip.jsonl",
        "cm-parity-external-tool-1",
    )

    /** An LLM 400: loop_error, error_message, stop_reason error, turn_finished, terminal loop_error last. */
    val LOOP_ERROR = ParityFixture("$DIR/loop-error-terminal.jsonl", "cm-parity-loop-error-1")

    /** The user cancels mid-turn; the other viewer's parked input is released with resume_queue. */
    val ABORT_THEN_RESUME = ParityFixture(
        "$DIR/abort-then-resume-queue.jsonl",
        "cm-parity-abort-1",
        EngineSetup(driver = AbortMidTurnDriver),
    )

    /** tool -> assistant -> tool -> assistant over three runs, the loop idling between rounds. */
    val MULTI_ROUND = ParityFixture(
        "$DIR/multi-round-agentic.jsonl",
        "cm-parity-multi-round-1",
    )

    /** Every complete recorded turn, in the order the gate runs them. */
    val ALL = listOf(
        THINKING,
        QUEUED,
        TOOL_CALL_AUTO_ALLOWED,
        APPROVAL_DENIED,
        EXTERNAL_TOOL,
        LOOP_ERROR,
        ABORT_THEN_RESUME,
        MULTI_ROUND,
    )

    /** Recordings that end mid-turn on purpose; replayed by their own tests, not the gate. */
    val PARTIAL = listOf("$DIR/session-lost-mid-turn.jsonl")
}
