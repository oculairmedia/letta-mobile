package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the shell's [MascotIdentityRegistry] current with every known agent's chosen identity:
 * the one on the agent itself (its metadata, the same field desktop writes), else the device's
 * cached / legacy setting. Re-derived whenever the roster changes; the edit-agent picker also writes
 * the registry directly so a fresh choice shows before the roster next refreshes. Agents without a
 * choice have no identity and draw their fallback.
 */
@Composable
fun MascotIdentitySync(
    agents: StateFlow<List<Agent>>,
    settings: SecureSettingsStore,
    registry: MascotIdentityRegistry,
) {
    val roster by agents.collectAsStateWithLifecycle()
    LaunchedEffect(roster) {
        registry.update(
            roster.mapNotNull { agent ->
                val id = agent.id.value
                resolveMascotIdentity(agent, settings.getString(mascotIdentitySettingsKey(id)))?.let { id to it }
            }.toMap(),
        )
    }
}
