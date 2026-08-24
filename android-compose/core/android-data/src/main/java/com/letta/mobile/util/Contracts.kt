package com.letta.mobile.util

import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.common.UiState
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Validates that [agent] is non-null and has a non-blank ID, returning the
 * agent with a non-null smart-cast so callers can skip redundant null checks.
 *
 * Usage:
 * ```
 * val agent = requireValidAgent(maybeAgent, { "Admin agent not found" })
 * // agent is now smart-cast to non-null Agent
 * chatWith(agent.id)
 * ```
 */
@OptIn(ExperimentalContracts::class)
fun requireValidAgent(
    agent: Agent?,
    lazyMessage: () -> Any = { "Agent must not be null and must have a non-blank ID" },
): Agent {
    contract { returns() implies (agent != null) }
    requireNotNull(agent, lazyMessage)
    require(agent.id.value.isNotBlank(), lazyMessage)
    return agent
}

/**
 * Validates that [state] is [UiState.Success], returning the state with a
 * smart-cast so callers can access `.data` without manual type checks.
 *
 * Usage:
 * ```
 * val success = requireLoadedState(uiState, { "Data not loaded yet" })
 * // success is now smart-cast to UiState.Success<T>
 * render(success.data)
 * ```
 */
@OptIn(ExperimentalContracts::class)
fun requireLoadedState(
    state: UiState<*>,
    lazyMessage: () -> Any = { "Expected Success state but was ${state::class.simpleName}" },
): UiState.Success<*> {
    contract { returns() implies (state is UiState.Success<*>) }
    require(state is UiState.Success<*>, lazyMessage)
    return state
}
