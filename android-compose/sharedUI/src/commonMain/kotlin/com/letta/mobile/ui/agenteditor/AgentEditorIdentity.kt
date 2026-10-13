package com.letta.mobile.ui.agenteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agenteditor.AgentEditorActions
import com.letta.mobile.data.agenteditor.AgentEditorState
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.mascot.AgentMascotIdentityWriter
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.MASCOT_IDENTITY_METADATA_KEY
import com.letta.mobile.ui.mascot.mascotIdentitySettingsKey
import com.letta.mobile.ui.mascot.resolveMascotIdentity
import com.letta.mobile.ui.mascot.withinAgentMetadata
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The mascot the editor shows and how a pick is kept. */
internal class AgentEditorIdentity(val current: MascotIdentity, val onChange: (MascotIdentity) -> Unit)

/**
 * An existing agent's pick is written at once (the device cache, the shell's registry so every tile
 * re-skins, the agent's metadata); a new agent's pick rides in the form's metadata until it is created.
 */
@Composable
internal fun rememberAgentEditorIdentity(
    state: AgentEditorState,
    actions: AgentEditorActions,
    options: AgentEditorPageOptions,
): AgentEditorIdentity {
    val agentId = state.agentId ?: return newAgentIdentity(state, actions)
    val registry = LocalMascotRegistry.current
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val writer = remember(agentId, options.settings, actions) {
        AgentMascotIdentityWriter(
            agentId = agentId,
            settings = options.settings,
            scope = scope,
            loadedMetadata = { latest.agent?.metadata },
            push = actions::writeMetadata,
        )
    }
    val current = registry.identities[agentId]
        ?: resolveMascotIdentity(agentId, state.agent, options.settings.getString(mascotIdentitySettingsKey(agentId)))
    return AgentEditorIdentity(current) { picked ->
        registry.identities[agentId] = picked
        writer.write(picked)
        options.onIdentityChange(agentId, picked)
    }
}

private fun newAgentIdentity(state: AgentEditorState, actions: AgentEditorActions): AgentEditorIdentity {
    val chosen = state.form.metadata[MASCOT_IDENTITY_METADATA_KEY]
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.let(MascotIdentity::decode)
    return AgentEditorIdentity(chosen ?: MascotIdentity.DEFAULT) { picked ->
        actions.update { copy(metadata = picked.withinAgentMetadata(metadata)) }
    }
}

/** A reading of the identity for its row: the body's name. */
internal val MascotIdentity.label: String
    get() = shape.name.lowercase().replaceFirstChar { it.titlecase() }

/** The settings store an [AgentEditorPageOptions] needs when the host has none to give (previews, tests). */
internal object NoSettingsStore : SecureSettingsStore {
    override fun getString(key: String, defaultValue: String?): String? = defaultValue
    override fun putString(key: String, value: String) = Unit
    override fun remove(key: String) = Unit
    override fun clear() = Unit
}
