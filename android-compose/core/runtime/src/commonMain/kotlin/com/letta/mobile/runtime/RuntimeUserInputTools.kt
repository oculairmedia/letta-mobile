package com.letta.mobile.runtime

/**
 * letta-code "runtime user-input" tools: tools whose whole purpose is to get an
 * answer from the human, not a yes/no permission decision. Mirrors letta-code's
 * `RUNTIME_USER_INPUT_TOOLS` (interactiveToolPolicy.ts).
 *
 * These MUST NOT be silently auto-approved: doing so closes the tool with no
 * answer (the tool returns a "Waiting for user response..." placeholder and the
 * agent stalls). The client must instead surface the query and return the user's
 * answer. See epic letta-mobile-vilsn.
 */
object RuntimeUserInputTools {
    const val ASK_USER_QUESTION = "AskUserQuestion"
    const val EXIT_PLAN_MODE = "ExitPlanMode"

    /** Tool names that require a real user answer rather than an auto-approval. */
    // ExitPlanMode stays auto-approved until every host has an actionable renderer;
    // classifying it as interactive without controls parks the turn permanently.
    val names: Set<String> = setOf(ASK_USER_QUESTION)

    fun requiresUserInput(toolName: String?): Boolean = toolName != null && toolName in names
}
