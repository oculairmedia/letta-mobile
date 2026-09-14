package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.presenceByAgent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * The mascots' shell (letta-mobile-8jtf3): one Rive host and one identity/presence registry for
 * the activity, kept current from the roster and from the app-wide run registry (every agent's
 * presence, wherever its runs are), provided to everything below through the shared locals so
 * any tile, chip or companion can draw an agent.
 */
@Composable
fun ProvideMascotShell(
    agents: StateFlow<List<Agent>>,
    settings: SecureSettingsStore,
    runs: ConversationRunRegistry,
    content: @Composable () -> Unit,
) {
    val host = rememberAndroidMascotHost()
    val registry = remember { MascotIdentityRegistry() }
    // Roster and run-presence collect stay off the first-display frame; tiles use empty
    // identity until the host is real (two vsyncs after composition, for TTFD).
    if (host !== NoMascotHost) {
        MascotIdentitySync(agents, settings, registry)
        val runState by runs.runs.collectAsStateWithLifecycle()
        LaunchedEffect(runState) { registry.updatePresence(runState.presenceByAgent()) }
    }
    CompositionLocalProvider(
        LocalMascotHost provides host,
        LocalMascotRegistry provides registry,
        content = content,
    )
}
