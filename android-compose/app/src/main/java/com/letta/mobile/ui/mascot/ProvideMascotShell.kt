package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * The mascots' shell (letta-mobile-8jtf3): one Rive host and one identity/presence registry for
 * the activity, kept current from the roster, provided to everything below through the shared
 * locals so any tile, chip or companion can draw an agent.
 */
@Composable
fun ProvideMascotShell(
    agents: StateFlow<List<Agent>>,
    settings: SecureSettingsStore,
    content: @Composable () -> Unit,
) {
    val host = rememberAndroidMascotHost()
    val registry = remember { MascotIdentityRegistry() }
    // Roster collect stays off the first-display frame; tiles use empty identity until then.
    if (host !== NoMascotHost) {
        MascotIdentitySync(agents, settings, registry)
    }
    CompositionLocalProvider(
        LocalMascotHost provides host,
        LocalMascotRegistry provides registry,
        content = content,
    )
}
