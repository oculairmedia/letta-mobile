package com.letta.mobile.runtime

/**
 * letta-code "runtime user-input" tools: tools whose whole purpose is to get an
 * answer from the human, not a yes/no permission decision. Mirrors letta-code's
 * `RUNTIME_USER_INPUT_TOOLS` (interactiveToolPolicy.ts), which is
 * `["AskUserQuestion", "ExitPlanMode"]` through 0.34.x.
 *
 * These MUST NOT be silently auto-approved, under any permission mode: doing so
 * closes the tool with no answer (the tool returns a "Waiting for user response..."
 * placeholder and the agent stalls), and for ExitPlanMode it lets a plan-mode run
 * start coding with no human gate. letta-code's own `resolveAppServerToolApproval`
 * skips the Unrestricted auto-allow for exactly this set. The client must instead
 * surface the query (the question card, or the plan card) and return the user's
 * answer. See epics letta-mobile-vilsn and letta-mobile-8fq4j.
 */
object RuntimeUserInputTools {
    const val ASK_USER_QUESTION = "AskUserQuestion"
    const val EXIT_PLAN_MODE = "ExitPlanMode"

    /** Tool names that require a real user answer rather than an auto-approval. */
    val names: Set<String> = setOf(ASK_USER_QUESTION, EXIT_PLAN_MODE)

    fun requiresUserInput(toolName: String?): Boolean = toolName != null && toolName in names
}
