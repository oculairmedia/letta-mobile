package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the shell's [MascotIdentityRegistry] current with every known agent's identity: the one on
 * the agent itself (its metadata, the same field desktop writes), else the device's cached / legacy
 * setting, else the identity generated from the agent id. Re-derived whenever the roster changes; the
 * edit-agent picker also writes the registry directly so a fresh choice shows before the roster next
 * refreshes.
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
            roster.associate { agent ->
                val id = agent.id.value
                id to resolveMascotIdentity(id, agent, settings.getString(mascotIdentitySettingsKey(id)))
            },
        )
    }
}
