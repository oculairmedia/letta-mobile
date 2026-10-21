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
 * any tile, chip or companion can draw an agent. Its [MascotTransport] carries each agent's
 * character between seats on the activity's [MascotTransportLayer] (letta-mobile-te3n1.8), by
 * each seat's live-or-still rule ([MascotTransport.followsSeatRule]): an idle agent's hero is its
 * still, and one scene per agent keeps the host's one-live-driver rule.
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
    val transport = remember { MascotTransport(followsSeatRule = true) }
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
        LocalMascotTransport provides transport,
        content = content,
    )
}
