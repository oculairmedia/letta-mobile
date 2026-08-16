package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CancellationException

/**
 * Stable loader bound to the session's repository. Remembered so the reading
 * effect keys off the conversation rather than restarting on every
 * recomposition.
 */
@Composable
internal fun rememberContextWindowLoader(
    repository: IAgentRepository,
): suspend (String, String?) -> ContextWindowOverview =
    remember(repository) {
        { agentId, conversationId -> repository.getContextWindow(agentId, conversationId) }
    }

/** Inputs that decide when the context window is re-read. */
internal data class ComposerContextUsageKey(
    val agentId: String?,
    val conversationId: String?,
    /** False while a turn is in flight — the reading is taken once it settles. */
    val settled: Boolean,
)

/**
 * Reads the focused conversation's context window whenever the conversation
 * changes or a turn settles. Mid-turn readings are skipped rather than polled:
 * the server only recounts once the turn is written back, so a reading taken
 * while the agent is still answering would show a stale total that then jumps.
 */
@Composable
internal fun rememberComposerContextUsage(
    key: ComposerContextUsageKey,
    load: (suspend (String, String?) -> ContextWindowOverview)?,
): ComposerContextUsageState {
    var state by remember { mutableStateOf(ComposerContextUsageState()) }
    LaunchedEffect(key, load) {
        val agentId = key.agentId
        if (load == null || agentId.isNullOrBlank()) {
            state = ComposerContextUsageState()
            return@LaunchedEffect
        }
        if (!key.settled) return@LaunchedEffect
        state = state.copy(loading = true, error = null)
        state = try {
            val overview = load(agentId, key.conversationId)
            ComposerContextUsageState(usage = ContextWindowUsage.from(overview))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Keep the last good reading on screen; the panel reports the miss
            // in place of a total rather than blanking the chip.
            state.copy(
                loading = false,
                error = failure.message?.takeIf { it.isNotBlank() }
                    ?: "Context window unavailable.",
            )
        }
    }
    return state
}
