package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.context.ContextWindowUsageKey
import com.letta.mobile.data.context.ContextWindowUsagePolicy
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CancellationException

/**
 * The focused conversation's context-window reading — the shell's one-call
 * entry point, so it binds a repository and a focus rather than assembling a
 * key and a loader at the call site.
 */
@Composable
internal fun rememberFocusedContextUsage(
    agentId: String?,
    conversationId: String?,
    settled: Boolean,
    repository: IAgentRepository,
): ContextWindowUsageState = rememberComposerContextUsage(
    key = ContextWindowUsageKey(agentId, conversationId, settled),
    load = rememberContextWindowLoader(repository),
)

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

/**
 * Desktop host binding for the context-window reading: runs the read as a
 * Compose effect and holds the result. When to read, what to keep on failure,
 * and what to drop when the focus changes all live in the shared
 * [ContextWindowUsagePolicy] so other clients behave identically.
 */
@Composable
internal fun rememberComposerContextUsage(
    key: ContextWindowUsageKey,
    load: (suspend (String, String?) -> ContextWindowOverview)?,
): ContextWindowUsageState {
    var state by remember { mutableStateOf(ContextWindowUsagePolicy.cleared()) }
    var readFor by remember { mutableStateOf<ContextWindowUsageKey?>(null) }
    LaunchedEffect(key, load) {
        if (load == null || !ContextWindowUsagePolicy.readable(key)) {
            // Includes the mid-turn case: drop another conversation's reading
            // immediately, but leave this one's in place until it is replaced.
            if (!key.sameIdentityAs(readFor)) {
                state = ContextWindowUsagePolicy.cleared()
                readFor = null
            }
            return@LaunchedEffect
        }
        state = ContextWindowUsagePolicy.reading(state, key, readFor)
        state = try {
            ContextWindowUsagePolicy.read(load(key.agentId.orEmpty(), key.conversationId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ContextWindowUsagePolicy.failed(state, failure.message)
        }
        readFor = key
    }
    return state
}
